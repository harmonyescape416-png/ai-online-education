package com.kzai.aigc.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.aigc.client.CourseAiItem;
import com.kzai.aigc.client.CourseClient;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.chunk.TextChunker;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.entity.KnowledgeIndexJob;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.mapper.KnowledgeIndexJobMapper;
import com.kzai.aigc.knowledge.parser.DocumentParser;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 知识文档管理（M9）：上传 / 查询 / 审核发布撤回 / 重解析 / 重建 / 删除 / 检索测试。
 * <p>
 * 安全与一致性约束：
 * <ul>
 *   <li>类型白名单（扩展名 + PDF 魔数）；大小上限；课程关联真实校验（courseId 必须真实可见）；</li>
 *   <li>存储路径服务端受控生成（UUID 文件名），绝不拼接用户输入文件名；</li>
 *   <li>SHA-256 唯一索引防重复上传；</li>
 *   <li>上传后 review_status=0 待审核；审核发布后触发解析/索引；仅「已发布」可被检索；</li>
 *   <li>所有变更操作先落任务表（状态机 + 补偿），不虚报成功。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAdminService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeIndexJobMapper jobMapper;
    private final AiProperties props;
    private final CourseClient courseClient;
    private final com.kzai.aigc.supervision.OperationAuditBridge audit; // M13 审计落库

    public KnowledgeDocument upload(MultipartFile file, String title, Long courseId,
                                    String accessLevel, long operator) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > props.getKnowledgeMaxFileSize()) {
            throw new BizException("文件超过大小上限（" + (props.getKnowledgeMaxFileSize() / 1024 / 1024) + "MB）");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(original);
        String docType = switch (ext) {
            case "pdf" -> KnowledgeDocument.TYPE_PDF;
            case "md", "markdown" -> KnowledgeDocument.TYPE_MARKDOWN;
            default -> throw new BizException("仅支持 PDF / Markdown 文件");
        };
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BizException("读取上传文件失败");
        }
        if (KnowledgeDocument.TYPE_PDF.equals(docType) && !DocumentParser.looksLikePdf(bytes)) {
            throw new BizException("文件内容与 PDF 格式不符");
        }
        if (courseId != null) {
            // 课程关联真实校验：课程必须存在且学员可见（上架 + 分类启用）
            R<CourseAiItem> course = courseClient.aiDetail(courseId);
            if (course == null || !course.isSuccess() || course.getData() == null) {
                throw new BizException("关联课程不存在或不可见");
            }
        }
        String access = normalizeAccess(accessLevel);
        if (KnowledgeDocument.ACCESS_PROTECTED.equals(access) && courseId == null) {
            throw new BizException("PROTECTED 级文档必须关联课程");
        }
        String sha256 = sha256Hex(bytes);

        var duplicate = Wrappers.<KnowledgeDocument>lambdaQuery().eq(KnowledgeDocument::getSha256, sha256);
        if (courseId == null) {
            duplicate.isNull(KnowledgeDocument::getCourseId);
        } else {
            duplicate.eq(KnowledgeDocument::getCourseId, courseId);
        }
        Long existed = documentMapper.selectCount(duplicate);
        if (existed != null && existed > 0) {
            throw new BizException("相同内容的文件已存在（SHA-256 重复），请勿重复上传");
        }

        // 受控存储路径：目录/UUID.扩展名，绝不使用用户输入文件名拼路径
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = Path.of(props.getKnowledgeStorageDir(),
                String.valueOf(java.time.LocalDate.now().getYear()),
                String.format(Locale.ROOT, "%02d", java.time.LocalDate.now().getMonthValue()));
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(storedName), bytes);
        } catch (IOException ex) {
            log.warn("[knowledge] 文件存储失败: {}", ex.getMessage());
            throw new BizException("文件存储失败，请稍后重试");
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle(title == null || title.isBlank() ? original : title.trim());
        doc.setDocType(docType);
        doc.setCourseId(courseId);
        doc.setFileName(sanitizeFileName(original));
        doc.setFilePath(dir.resolve(storedName).toString());
        doc.setFileSize((long) bytes.length);
        doc.setSha256(sha256);
        doc.setCharCount(0);
        doc.setChunkCount(0);
        doc.setIndexStatus(KnowledgeDocument.INDEX_PENDING);
        doc.setAccessLevel(access);
        doc.setReviewStatus(KnowledgeDocument.REVIEW_PENDING);
        doc.setPublisher(operator);
        doc.setCreateTime(LocalDateTime.now());
        doc.setUpdateTime(LocalDateTime.now());
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // uk_sha256 唯一索引（含已逻辑删除历史）——同内容历史上传过即拒绝重复入库
            throw new BizException("相同内容的文件已存在（SHA-256 重复），请勿重复上传");
        }
        return doc;
    }

    public Page<KnowledgeDocument> list(long page, long size, String keyword, Integer status) {
        return documentMapper.selectPage(new Page<>(page, Math.min(Math.max(size, 1), 100)),
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(KnowledgeDocument::getTitle, keyword.trim())
                                        .or().like(KnowledgeDocument::getFileName, keyword.trim()))
                        .eq(status != null, KnowledgeDocument::getIndexStatus, status)
                        .orderByDesc(KnowledgeDocument::getCreateTime));
    }

    public KnowledgeDocument detail(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException("文档不存在");
        }
        return doc;
    }

    /** 审核发布 / 撤回：仅「已发布」可被检索；发布时触发解析索引 */
    public KnowledgeDocument review(Long id, boolean publish, long operator) {
        LocalDateTime now = LocalDateTime.now();
        int changed = documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                .setSql("publish_generation = publish_generation + 1")
                .set(KnowledgeDocument::getReviewStatus, publish ? KnowledgeDocument.REVIEW_PUBLISHED
                        : KnowledgeDocument.REVIEW_WITHDRAWN)
                .set(KnowledgeDocument::getReviewer, operator)
                .set(KnowledgeDocument::getReviewTime, now)
                .set(KnowledgeDocument::getUpdateTime, now)
                .eq(KnowledgeDocument::getId, id)
                .eq(KnowledgeDocument::getDeleted, 0));
        if (changed != 1) {
            throw new BizException("文档不存在或已删除，不能审核");
        }
        KnowledgeDocument doc = detail(id);
        audit.write(operator, String.valueOf(operator), "KNOWLEDGE",
                publish ? "KNOWLEDGE_PUBLISH" : "KNOWLEDGE_WITHDRAW", "ai_knowledge_document", id,
                "{\"title\":\"" + esc(doc.getTitle()) + "\",\"generation\":" + doc.getPublishGeneration() + "}");
        if (publish) {
            enqueue(doc.getId(), KnowledgeIndexJob.ACTION_REINDEX, operator, doc.getPublishGeneration());
        }
        return doc;
    }

    public void reparse(Long id, long operator) {
        KnowledgeDocument doc = detail(id);
        enqueue(id, KnowledgeIndexJob.ACTION_REPARSE, operator, generationOf(doc));
    }

    public void reindex(Long id, long operator) {
        KnowledgeDocument doc = detail(id);
        enqueue(id, KnowledgeIndexJob.ACTION_REINDEX, operator, generationOf(doc));
    }

    /** 删除：先逻辑删除（立即不可授权召回），再由任务清理 ES 向量（失败可补偿） */
    public void delete(Long id, long operator) {
        KnowledgeDocument doc = detail(id);
        LocalDateTime now = LocalDateTime.now();
        int removed = documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                .set(KnowledgeDocument::getDeleted, 1)
                .setSql("publish_generation = publish_generation + 1")
                .set(KnowledgeDocument::getUpdateTime, now)
                .eq(KnowledgeDocument::getId, id)
                .eq(KnowledgeDocument::getDeleted, 0));
        if (removed != 1) {
            throw new BizException("文档不存在或已删除");
        }
        doc.setDeleted(1);
        // M13：知识库删除审计
        audit.write(operator, String.valueOf(operator), "KNOWLEDGE", "KNOWLEDGE_DELETE",
                "ai_knowledge_document", id,
                "{\"title\":\"" + esc(doc.getTitle()) + "\"}");
        enqueue(id, KnowledgeIndexJob.ACTION_DELETE, operator, generationOf(doc));
    }

    /**
     * 教师对已审核但索引失败的资料重新入队。不新建文档，不改变访问级别和来源版本。
     */
    public Map<String, Object> retryTeacherResource(long resourceId) {
        String version = String.valueOf(resourceId);
        KnowledgeDocument doc = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getSourceResourceId, resourceId)
                .eq(KnowledgeDocument::getSourceVersion, version)
                .last("LIMIT 1"));
        if (doc == null || Integer.valueOf(1).equals(doc.getDeleted())) {
            throw new BizException("没有可重试的知识文档");
        }
        if (doc.getReviewStatus() == null || doc.getReviewStatus() == KnowledgeDocument.REVIEW_PENDING) {
            throw new BizException("文档仍待审核，不能绕过审核直接索引");
        }
        if (doc.getReviewStatus() == KnowledgeDocument.REVIEW_WITHDRAWN) {
            throw new BizException("文档已撤回，不能恢复为可检索");
        }
        if (doc.getIndexStatus() == null || doc.getIndexStatus() != KnowledgeDocument.INDEX_FAILED) {
            KnowledgeIndexJob active = activeJob(doc.getId());
            if (active != null) {
                return retryView(doc, active, true);
            }
            throw new BizException("当前状态不能重试索引");
        }
        EnqueueResult queued = enqueue(doc.getId(), KnowledgeIndexJob.ACTION_REINDEX,
                doc.getPublisher() == null ? 0L : doc.getPublisher(), generationOf(doc));
        documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                .set(KnowledgeDocument::getIndexStatus, KnowledgeDocument.INDEX_PENDING)
                .set(KnowledgeDocument::getFailReason, null)
                .set(KnowledgeDocument::getUpdateTime, LocalDateTime.now())
                .eq(KnowledgeDocument::getId, doc.getId())
                .eq(KnowledgeDocument::getDeleted, 0)
                .eq(KnowledgeDocument::getReviewStatus, KnowledgeDocument.REVIEW_PUBLISHED)
                .eq(KnowledgeDocument::getPublishGeneration, generationOf(doc))
                .eq(KnowledgeDocument::getIndexStatus, KnowledgeDocument.INDEX_FAILED));
        doc.setIndexStatus(KnowledgeDocument.INDEX_PENDING);
        doc.setFailReason(null);
        return retryView(doc, queued.job(), queued.alreadyQueued());
    }

    private Map<String, Object> retryView(KnowledgeDocument doc, KnowledgeIndexJob job, boolean alreadyQueued) {
        Map<String, Object> view = sourceView(doc);
        view.put("jobId", job.getId() == null ? null : String.valueOf(job.getId()));
        view.put("phase", "INDEXING");
        view.put("alreadyQueued", alreadyQueued);
        view.put("accessLevel", doc.getAccessLevel());
        return view;
    }

    private KnowledgeIndexJob activeJob(Long docId) {
        return jobMapper.selectOne(Wrappers.<KnowledgeIndexJob>lambdaQuery()
                .eq(KnowledgeIndexJob::getDocId, docId)
                .in(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_PENDING, KnowledgeIndexJob.STATUS_RUNNING)
                .last("LIMIT 1"));
    }

    private static int generationOf(KnowledgeDocument doc) {
        return doc.getPublishGeneration() == null ? 0 : doc.getPublishGeneration();
    }

    // ------------------------------------------------------------------

    public EnqueueResult enqueue(Long docId, String action, long operator, Integer generation) {
        KnowledgeIndexJob existing = activeJob(docId);
        if (existing != null) {
            return new EnqueueResult(existing, true);
        }
        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setDocId(docId);
        job.setAction(action);
        job.setStatus(KnowledgeIndexJob.STATUS_PENDING);
        job.setAttempt(0);
        job.setOperator(operator);
        job.setPublishGeneration(generation == null ? 0 : generation);
        LocalDateTime now = LocalDateTime.now();
        job.setCreateTime(now);
        job.setUpdateTime(now);
        try {
            jobMapper.insert(job);
            return new EnqueueResult(job, false);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            KnowledgeIndexJob raced = activeJob(docId);
            if (raced != null) {
                return new EnqueueResult(raced, true);
            }
            throw new BizException("索引任务正在创建，请稍后刷新");
        }
    }

    public record EnqueueResult(KnowledgeIndexJob job, boolean alreadyQueued) {
    }

    private static String normalizeAccess(String accessLevel) {
        String a = accessLevel == null || accessLevel.isBlank()
                ? KnowledgeDocument.ACCESS_PUBLIC : accessLevel.toUpperCase(Locale.ROOT);
        return switch (a) {
            case KnowledgeDocument.ACCESS_PUBLIC, KnowledgeDocument.ACCESS_PROTECTED,
                    KnowledgeDocument.ACCESS_INTERNAL -> a;
            default -> throw new BizException("非法访问级别: " + accessLevel);
        };
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }

    /** 供任务执行器读取原文 */
    public byte[] readFile(KnowledgeDocument doc) {
        try {
            return Files.readAllBytes(Path.of(doc.getFilePath()));
        } catch (IOException ex) {
            throw new IllegalStateException("读取存储文件失败: " + doc.getFilePath(), ex);
        }
    }

    /** 供任务执行器读取清洗后正文 */
    public String parseText(KnowledgeDocument doc, byte[] bytes) {
        return new DocumentParser().parse(doc.getDocType(), bytes);
    }

    /** 供任务执行器切片 */
    public List<String> chunk(String text) {
        TextChunker chunker = new TextChunker();
        return chunker.chunk(text);
    }

    /** 编码只读入口（测试用） */
    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** detail JSON 转义（引号/反斜杠/换行） */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }


    /**
     * 教师资料入库。先按资源 ID 找回已有文档（远程成功、调用方超时后的重试），
     * 没有时再向课程服务取真实字节。网络调用不包在数据库事务里。
     */
    public Map<String, Object> importTeacherResource(long resourceId) {
        String version = String.valueOf(resourceId);
        KnowledgeDocument existing = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getSourceResourceId, resourceId)
                .eq(KnowledgeDocument::getSourceVersion, version)
                .last("LIMIT 1"));
        if (existing != null) {
            return sourceView(existing);
        }
        R<Map<String, Object>> remote = courseClient.resourceContent(resourceId);
        if (remote == null || !remote.isSuccess() || remote.getData() == null) {
            throw new BizException(remote == null || remote.getMsg() == null
                    ? "读取课程资料失败" : remote.getMsg());
        }
        Map<String, Object> meta = remote.getData();
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(String.valueOf(meta.get("contentBase64")));
        } catch (IllegalArgumentException ex) {
            throw new BizException("课程资料内容无法解码");
        }
        if (bytes.length > props.getKnowledgeMaxFileSize()) {
            throw new BizException("文件超过知识库导入上限 "
                    + (props.getKnowledgeMaxFileSize() / 1024 / 1024)
                    + "MB。课程附件上限可以更大，超限文件仍可留在课程里，但不能加入知识库");
        }
        String fileName = String.valueOf(meta.getOrDefault("fileName", "resource"));
        String ext = extensionOf(fileName);
        String docType = switch (ext) {
            case "pdf" -> KnowledgeDocument.TYPE_PDF;
            case "md", "markdown" -> KnowledgeDocument.TYPE_MARKDOWN;
            default -> throw new BizException("当前只支持把 PDF 或 Markdown 加入知识库，该文件仍可作为课程附件");
        };
        if (KnowledgeDocument.TYPE_PDF.equals(docType) && !com.kzai.aigc.knowledge.parser.DocumentParser.looksLikePdf(bytes)) {
            throw new BizException("文件内容与 PDF 格式不符");
        }
        String text;
        try {
            text = new com.kzai.aigc.knowledge.parser.DocumentParser().parse(docType, bytes);
        } catch (com.kzai.aigc.knowledge.parser.DocumentParser.ParseException ex) {
            throw new BizException(ex.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw new BizException("文档没有可提取的正文，未建立审核任务");
        }
        long courseId = Long.parseLong(String.valueOf(meta.get("courseId")));
        boolean free = Boolean.TRUE.equals(meta.get("free")) || "1".equals(String.valueOf(meta.get("free")));
        long uploader = meta.get("uploaderId") == null ? 0L : Long.parseLong(String.valueOf(meta.get("uploaderId")));
        String sha = sha256Hex(bytes);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = Path.of(props.getKnowledgeStorageDir(),
                String.valueOf(java.time.LocalDate.now().getYear()),
                String.format(Locale.ROOT, "%02d", java.time.LocalDate.now().getMonthValue()));
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(storedName), bytes);
        } catch (IOException ex) {
            throw new BizException("知识库文件保存失败，请稍后重试");
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("[教师提交] " + sanitizeFileName(fileName));
        doc.setDocType(docType);
        doc.setCourseId(courseId);
        doc.setFileName(sanitizeFileName(fileName));
        doc.setFilePath(dir.resolve(storedName).toString());
        doc.setFileSize((long) bytes.length);
        doc.setSha256(sha);
        doc.setCharCount(0);
        doc.setChunkCount(0);
        doc.setIndexVersion(0);
        doc.setIndexStatus(KnowledgeDocument.INDEX_PENDING);
        doc.setReviewStatus(KnowledgeDocument.REVIEW_PENDING);
        doc.setAccessLevel(free ? KnowledgeDocument.ACCESS_PUBLIC : KnowledgeDocument.ACCESS_PROTECTED);
        doc.setPublisher(uploader);
        doc.setSourceResourceId(resourceId);
        doc.setSourceVersion(version);
        doc.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            KnowledgeDocument raced = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                    .eq(KnowledgeDocument::getSourceResourceId, resourceId)
                    .eq(KnowledgeDocument::getSourceVersion, version)
                    .last("LIMIT 1"));
            if (raced != null) {
                return sourceView(raced);
            }
            throw new BizException("知识文档保存冲突，请重试");
        }
        log.info("[kb-import] resource={} doc={} sha={} access={}", resourceId, doc.getId(), sha, doc.getAccessLevel());
        return sourceView(doc);
    }

    public List<Map<String, Object>> sourceStatus(List<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        return documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
                        .in(KnowledgeDocument::getSourceResourceId, resourceIds))
                .stream().map(this::sourceView).toList();
    }

    /** 资料删除或被新版本替换后，旧来源立即不可检索。 */
    public int withdrawByResource(long resourceId) {
        List<KnowledgeDocument> docs = documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getSourceResourceId, resourceId));
        int n = 0;
        for (KnowledgeDocument doc : docs) {
            if (doc.getReviewStatus() != null && doc.getReviewStatus() == KnowledgeDocument.REVIEW_WITHDRAWN) {
                continue;
            }
            documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                    .setSql("publish_generation = publish_generation + 1")
                    .set(KnowledgeDocument::getReviewStatus, KnowledgeDocument.REVIEW_WITHDRAWN)
                    .set(KnowledgeDocument::getUpdateTime, LocalDateTime.now())
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .eq(KnowledgeDocument::getDeleted, 0));
            jobMapper.update(null, Wrappers.<com.kzai.aigc.knowledge.entity.KnowledgeIndexJob>lambdaUpdate()
                    .set(com.kzai.aigc.knowledge.entity.KnowledgeIndexJob::getStatus,
                            com.kzai.aigc.knowledge.entity.KnowledgeIndexJob.STATUS_FAILED)
                    .set(com.kzai.aigc.knowledge.entity.KnowledgeIndexJob::getError, "来源资料已删除或替换，任务取消")
                    .set(com.kzai.aigc.knowledge.entity.KnowledgeIndexJob::getUpdateTime, LocalDateTime.now())
                    .eq(com.kzai.aigc.knowledge.entity.KnowledgeIndexJob::getDocId, doc.getId())
                    .in(com.kzai.aigc.knowledge.entity.KnowledgeIndexJob::getStatus,
                            com.kzai.aigc.knowledge.entity.KnowledgeIndexJob.STATUS_PENDING,
                            com.kzai.aigc.knowledge.entity.KnowledgeIndexJob.STATUS_RUNNING));
            n++;
        }
        return n;
    }

    private Map<String, Object> sourceView(KnowledgeDocument doc) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("docId", doc.getId() == null ? null : String.valueOf(doc.getId()));
        view.put("resourceId", doc.getSourceResourceId() == null ? null : String.valueOf(doc.getSourceResourceId()));
        view.put("sourceVersion", doc.getSourceVersion());
        view.put("reviewStatus", doc.getReviewStatus());
        view.put("indexStatus", doc.getIndexStatus());
        view.put("failReason", doc.getFailReason());
        view.put("accessLevel", doc.getAccessLevel());
        view.put("phase", phaseOf(doc));
        boolean retryable = doc.getReviewStatus() != null
                && doc.getReviewStatus() == KnowledgeDocument.REVIEW_PUBLISHED
                && doc.getIndexStatus() != null
                && doc.getIndexStatus() == KnowledgeDocument.INDEX_FAILED;
        view.put("retryable", retryable);
        return view;
    }

    static String phaseOf(KnowledgeDocument doc) {
        if (doc.getReviewStatus() != null && doc.getReviewStatus() == KnowledgeDocument.REVIEW_WITHDRAWN) {
            return "WITHDRAWN";
        }
        if (doc.getIndexStatus() != null && doc.getIndexStatus() == KnowledgeDocument.INDEX_FAILED) {
            return "FAILED";
        }
        if (doc.getReviewStatus() == null || doc.getReviewStatus() == KnowledgeDocument.REVIEW_PENDING) {
            return "REVIEW_PENDING";
        }
        if (doc.getIndexStatus() != null && doc.getIndexStatus() == KnowledgeDocument.INDEX_SUCCESS) {
            return "SEARCHABLE";
        }
        return "INDEXING";
    }

    /**
     * M15.21-E：教师「加入知识库」审核任务创建（管理员可见待审列表）。
     * 仅登记元数据（status=REVIEW_PENDING，index_status=PENDING）——文件实体由管理员审核通过后
     * 从课程资源下载并走既有 upload→解析→向量化→发布流程（不绕过审核）。
     */
    @Transactional
    public com.kzai.aigc.knowledge.entity.KnowledgeDocument createReviewTask(
            String fileName, Long courseId, String fileUrl, Long uploaderId) {
        com.kzai.aigc.knowledge.entity.KnowledgeDocument doc =
                new com.kzai.aigc.knowledge.entity.KnowledgeDocument();
        doc.setTitle("[教师提交] " + fileName);
        doc.setDocType("teacher_submitted");
        doc.setCourseId(courseId);
        doc.setFileName(fileName);
        doc.setFilePath(fileUrl); // course 服务受控路径，管理员审核时下载
        doc.setFileSize(0L);
        doc.setSha256("");
        doc.setCharCount(0);
        doc.setChunkCount(0);
        doc.setIndexVersion(0);
        doc.setIndexStatus(com.kzai.aigc.knowledge.entity.KnowledgeDocument.INDEX_PENDING);
        doc.setReviewStatus(com.kzai.aigc.knowledge.entity.KnowledgeDocument.REVIEW_PENDING);
        doc.setPublisher(uploaderId);
        doc.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);
        documentMapper.insert(doc);
        log.info("[kb-review] 教师提交知识库审核任务 docId={} file={} courseId={}", doc.getId(), fileName, courseId);
        return doc;
    }
}
