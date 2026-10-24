package com.kzai.aigc.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.embedding.KnowledgeEmbeddingService;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.entity.KnowledgeIndexJob;
import com.kzai.aigc.knowledge.index.EsKnowledgeGateway;
import com.kzai.aigc.knowledge.mapper.KnowledgeDocumentMapper;
import com.kzai.aigc.knowledge.mapper.KnowledgeIndexJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识索引任务执行器（M9）：
 * <ul>
 *   <li>状态机：待处理 → 乐观抢占（status 0→1）→ 执行 → 成功/失败；</li>
 *   <li>失败 attempt+1，未达上限重新入队（补偿）；达上限置失败并写原因；</li>
 *   <li>重启补偿：应用就绪时把「执行中」任务重置为「待处理」（任务状态可恢复）；</li>
 *   <li>重建一致性：新版本索引 bulk 完成 → 数量核验 → 别名原子切换；
 *       新版本验证成功前旧索引与别名不动（旧知识库持续可用）；</li>
 *   <li>ES 写入失败 → 任务失败落库，绝不虚报成功。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexJobRunner {

    private final KnowledgeIndexJobMapper jobMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeAdminService adminService;
    private final EsKnowledgeGateway es;
    private final KnowledgeEmbeddingService embedding;
    private final AiProperties props;

    /** 重启补偿：执行中任务重置为待处理 */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        int reset = jobMapper.update(null, Wrappers.<KnowledgeIndexJob>lambdaUpdate()
                .set(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_PENDING)
                .set(KnowledgeIndexJob::getUpdateTime, LocalDateTime.now())
                .eq(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_RUNNING));
        if (reset > 0) {
            log.info("[knowledge.job] 重启补偿：{} 个执行中任务已重置为待处理", reset);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void poll() {
        List<KnowledgeIndexJob> jobs = jobMapper.selectList(Wrappers.<KnowledgeIndexJob>lambdaQuery()
                .eq(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_PENDING)
                .orderByAsc(KnowledgeIndexJob::getCreateTime)
                .last("LIMIT 3"));
        for (KnowledgeIndexJob job : jobs) {
            // 乐观抢占：status 0→1（多实例下仅一个执行者成功）
            int claimed = jobMapper.update(null, Wrappers.<KnowledgeIndexJob>lambdaUpdate()
                    .set(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_RUNNING)
                    .set(KnowledgeIndexJob::getUpdateTime, LocalDateTime.now())
                    .eq(KnowledgeIndexJob::getId, job.getId())
                    .eq(KnowledgeIndexJob::getStatus, KnowledgeIndexJob.STATUS_PENDING));
            if (claimed != 1) {
                continue;
            }
            job.setStatus(KnowledgeIndexJob.STATUS_RUNNING);
            execute(job);
        }
    }

    /** 已抢占的任务直接执行。回归测试在 ES 写入处卡住，用来构造撤回时序。 */
    void executeClaimed(KnowledgeIndexJob job) {
        execute(job);
    }

    private void execute(KnowledgeIndexJob job) {
        try {
            switch (job.getAction()) {
                case KnowledgeIndexJob.ACTION_REPARSE -> doReparse(job);
                case KnowledgeIndexJob.ACTION_REINDEX -> doReindex(job);
                case KnowledgeIndexJob.ACTION_DELETE -> doDelete(job);
                default -> failJob(job, "未知任务动作: " + job.getAction());
            }
        } catch (Exception ex) {
            log.warn("[knowledge.job] 任务执行失败 id={} action={}: {}", job.getId(), job.getAction(), ex.getMessage());
            failJob(job, ex.getMessage());
        }
    }

    /** 解析 + 切片（不写 ES）：charCount/chunkCount 落库，index_status=2 已切片 */
    private void doReparse(KnowledgeIndexJob job) {
        KnowledgeDocument doc = requireDoc(job.getDocId());
        byte[] bytes = adminService.readFile(doc);
        String text = adminService.parseText(doc, bytes);
        List<String> chunks = adminService.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档清洗后无有效切片（内容为空或不可解析）");
        }
        int parsed = documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                .set(KnowledgeDocument::getCharCount, text.codePointCount(0, text.length()))
                .set(KnowledgeDocument::getChunkCount, chunks.size())
                .set(KnowledgeDocument::getIndexStatus, KnowledgeDocument.INDEX_CHUNKED)
                .set(KnowledgeDocument::getFailReason, null)
                .set(KnowledgeDocument::getUpdateTime, LocalDateTime.now())
                .eq(KnowledgeDocument::getId, doc.getId())
                .eq(KnowledgeDocument::getDeleted, 0)
                .eq(KnowledgeDocument::getPublishGeneration,
                        job.getPublishGeneration() == null ? 0 : job.getPublishGeneration()));
        if (parsed != 1) {
            abortWithoutSuccess(job, "解析结果未写入：文档已删除、撤回或审核轮次已变化");
            return;
        }
        succeed(job);
        log.info("[knowledge.job] 解析完成 doc={} chars={} chunks={}", doc.getId(), doc.getCharCount(), doc.getChunkCount());
    }

    /**
     * 重建索引（M9 补充修复 + M11 原子性加固：<b>版本化发布</b>）。
     * <p>
     * M9 就地重建修复了「重建 A 导致 B 消失」，但仍存在部分覆盖窗口：切片按
     * {@code _id=docId_chunkIndex} 覆盖写，中途失败会留下「新旧混用」（M11 故障注入实测）。
     * <p>
     * M11 版本化发布流程（用户批准：不改表、不改索引架构，mapping 已有 indexVersion 字段）：
     * <ol>
     *   <li><b>重建前清理</b>：删除该文档「非当前已发布版本」的切片（上次中断的孤儿）；</li>
     *   <li>解析 → 切片 → Embedding，生成新发布版本号 {@code newVer}；</li>
     *   <li>写入<b>新版本切片</b>（{@code chunkId=docId_newVer_i}，{@code indexVersion=newVer}），
     *       <b>不覆盖、不删除任何旧切片</b>；</li>
     *   <li>{@code countByDocIdVersion(docId,newVer)} 数量核验，不等则失败（旧版本仍完整可用）；</li>
     *   <li><b>发布切换</b>：更新 MySQL {@code index_version=newVer}（权威版本，单文档原子更新）；</li>
     *   <li>发布后清理：删除该文档「非 newVer」的切片（此时才允许破坏旧版本）。</li>
     * </ol>
     * 检索侧以 MySQL 权威版本为准过滤 hit（{@code hit.indexVersion != doc.index_version} 丢弃），
     * 因此任一阶段中断都只会「未发布新切片不可召回」，旧切片完整可用，绝不出现新旧混用。
     * 另由 {@code OrphanChunkCleaner} 定时兜底清理孤儿切片（策略 C：重建前清理 + 定时补偿）。
     */
    private void doReindex(KnowledgeIndexJob job) {
        KnowledgeDocument doc = requireDoc(job.getDocId());
        if (doc.getReviewStatus() == null || doc.getReviewStatus() != KnowledgeDocument.REVIEW_PUBLISHED) {
            throw new IllegalStateException("仅「已发布」文档可建立索引");
        }
        byte[] bytes = adminService.readFile(doc);
        String text = adminService.parseText(doc, bytes);
        List<String> chunks = adminService.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档清洗后无有效切片");
        }

        int dims = embedding.dimensions();
        int physicalVersion = currentVersionOrBootstrap(dims);
        // 新发布版本号：首次沿用物理索引版本（兼容既有数据），其后单调递增
        int published = doc.getIndexVersion() == null ? physicalVersion : doc.getIndexVersion();
        int newVer = published + 1;

        // ① 重建前清理：删除「非当前已发布版本」的孤儿切片（上次中断残留）
        long purgedOrphan = es.deleteByDocIdVersionNeq(doc.getId(), published);
        if (purgedOrphan < 0) {
            throw new IllegalStateException("孤儿切片清理失败（不影响旧版本可见性，任务待补偿重试）");
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("chunkId", doc.getId() + "_v" + newVer + "_" + i);
            d.put("docId", doc.getId());
            d.put("chunkIndex", i);
            d.put("indexVersion", newVer);
            d.put("docTitle", doc.getTitle());
            d.put("courseId", doc.getCourseId());
            d.put("accessLevel", doc.getAccessLevel());
            d.put("reviewStatus", 1);
            d.put("text", chunks.get(i));
            d.put("vector", embedding.embed(chunks.get(i)));
            docs.add(d);
        }
        // ② 写入新版本切片（不触碰旧版本切片）
        int written = es.bulkIndex(physicalVersion, docs);
        if (written < 0) {
            throw new IllegalStateException("ES 批量写入失败（旧版本未受影响，任务待补偿重试）");
        }
        if (written != chunks.size()) {
            throw new IllegalStateException("ES 写入数量不一致 written=" + written + " expect=" + chunks.size());
        }
        // ③ 新版本数量核验（只有写全才允许发布）
        long newCount = es.countByDocIdVersion(doc.getId(), newVer);
        if (newCount != chunks.size()) {
            throw new IllegalStateException("ES 新版本切片核验失败 count=" + newCount
                    + " expect=" + chunks.size() + "（旧版本仍可用，未发布）");
        }

        int publishedRows = documentMapper.publishIfCurrent(
                text.codePointCount(0, text.length()), chunks.size(), newVer, LocalDateTime.now(),
                doc.getId(), job.getPublishGeneration() == null ? 0 : job.getPublishGeneration());
        if (publishedRows != 1) {
            abortWithoutSuccess(job, "发布资格已失效：文档已撤回、删除或审核轮次已变化，新切片未成为权威版本");
            return;
        }

        // ⑤ 发布后清理旧版本切片（此时破坏旧版本是安全的）
        long removedOld = es.deleteByDocIdVersionNeq(doc.getId(), newVer);
        if (removedOld < 0) {
            throw new IllegalStateException("旧版本切片清理失败（新版本已发布，定时补偿将兜底清理）");
        }
        long count = es.countByDocId(doc.getId());
        if (count != chunks.size()) {
            throw new IllegalStateException("ES 切片数量核验失败 count=" + count + " expect=" + chunks.size());
        }

        succeed(job);
        log.info("[knowledge.job] 版本化重建发布成功 doc={} newVer={} chunks={} dims={} orphanPurged={} oldRemoved={}",
                doc.getId(), newVer, chunks.size(), dims, purgedOrphan, removedOld);
    }

    /** 当前别名版本；别名不存在（冷启动）则创建 v1 并挂别名 */
    private int currentVersionOrBootstrap(int dims) {
        Integer v = currentAliasVersion();
        if (v != null) {
            return v;
        }
        if (!es.createVersionedIndex(1, dims)) {
            throw new IllegalStateException("创建初始 ES 索引失败 v1");
        }
        if (!es.swapAlias(1, null)) {
            es.deletePhysicalIndex(1);
            throw new IllegalStateException("初始别名挂载失败");
        }
        return 1;
    }

    /** 删除：清理 ES 向量（逻辑删除已在入库时生效，立即不可召回） */
    private void doDelete(KnowledgeIndexJob job) {
        long deleted = es.deleteByDocId(job.getDocId());
        if (deleted < 0) {
            throw new IllegalStateException("ES 向量清理失败（任务保留待补偿）");
        }
        succeed(job);
        log.info("[knowledge.job] 向量清理完成 doc={} deleted={}", job.getDocId(), deleted);
    }

    /** 当前别名物理索引版本号；别名不存在或不可解析返回 null */
    private Integer currentAliasVersion() {
        String current = es.currentAliasedIndex();
        if (current == null || !current.startsWith(props.getKnowledgeIndexPrefix() + "-v")) {
            return null;
        }
        try {
            return Integer.parseInt(current.substring(props.getKnowledgeIndexPrefix().length() + 2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void abortWithoutSuccess(KnowledgeIndexJob job, String reason) {
        job.setStatus(KnowledgeIndexJob.STATUS_FAILED);
        job.setError(truncate(reason, 480));
        job.setUpdateTime(LocalDateTime.now());
        jobMapper.updateById(job);
        log.warn("[knowledge.job] 放弃发布 id={} doc={}: {}", job.getId(), job.getDocId(), job.getError());
    }

    private void succeed(KnowledgeIndexJob job) {
        job.setStatus(KnowledgeIndexJob.STATUS_SUCCESS);
        job.setUpdateTime(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void failJob(KnowledgeIndexJob job, String error) {
        job.setAttempt(job.getAttempt() == null ? 1 : job.getAttempt() + 1);
        job.setError(error == null ? "未知错误" : truncate(error, 480));
        KnowledgeDocument current = documentMapper.selectById(job.getDocId());
        int jobGeneration = job.getPublishGeneration() == null ? 0 : job.getPublishGeneration();
        boolean eligible = current != null
                && (current.getDeleted() == null || current.getDeleted() == 0)
                && current.getReviewStatus() != null
                && current.getReviewStatus() == KnowledgeDocument.REVIEW_PUBLISHED
                && (current.getPublishGeneration() == null ? 0 : current.getPublishGeneration()) == jobGeneration;
        if (!eligible || job.getAttempt() >= KnowledgeIndexJob.MAX_ATTEMPT) {
            job.setStatus(KnowledgeIndexJob.STATUS_FAILED);
            if (!eligible) {
                job.setError(truncate(job.getError() + "；来源已失效，不再补偿重试", 480));
            }
            // 只在本任务仍对应已发布轮次时写失败原因，避免把撤回文档重新标成可重试失败
            documentMapper.update(null, Wrappers.<KnowledgeDocument>lambdaUpdate()
                    .set(KnowledgeDocument::getIndexStatus, KnowledgeDocument.INDEX_FAILED)
                    .set(KnowledgeDocument::getFailReason, job.getError())
                    .set(KnowledgeDocument::getUpdateTime, LocalDateTime.now())
                    .eq(KnowledgeDocument::getId, job.getDocId())
                    .eq(KnowledgeDocument::getDeleted, 0)
                    .eq(KnowledgeDocument::getReviewStatus, KnowledgeDocument.REVIEW_PUBLISHED)
                    .eq(KnowledgeDocument::getPublishGeneration, jobGeneration));
        } else {
            job.setStatus(KnowledgeIndexJob.STATUS_PENDING); // 补偿重试
        }
        job.setUpdateTime(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private KnowledgeDocument requireDoc(Long docId) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new IllegalStateException("文档不存在（可能已物理清理）");
        }
        return doc;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
