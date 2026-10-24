package com.kzai.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.AuthException;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.ResultCode;
import com.kzai.course.dto.ResourceBindRequest;
import com.kzai.course.entity.Course;
import com.kzai.course.entity.CourseResource;
import com.kzai.course.mapper.CourseMapper;
import com.kzai.course.mapper.CourseResourceMapper;
import com.kzai.course.vo.ResourceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 课程资料与文件（M3，扩展表 course_resource）。
 * <p>
 * 上传权限：教师/管理员；下载访问控制：登录 + （课程已上架且分类启用 / 本人上传 / 后台管理员）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final CourseResourceMapper resourceMapper;
    private final CourseMapper courseMapper;
    private final CourseSupport courseSupport;
    private final CategoryService categoryService;
    private final FileStorageService fileStorageService;
    private final TeacherCourseService teacherCourseService;
    private final ChapterService chapterService;
    private final EntitlementProvider entitlementProvider;
    private final com.kzai.course.client.AigcKnowledgeClient aigcKnowledgeClient;

    /** 上传文件：落盘 + 登记元数据，返回受控访问路径 /files/{id}/download */
    @Transactional
    public CourseResource upload(MultipartFile file) {
        String relativePath = fileStorageService.store(file);
        CourseResource resource = new CourseResource();
        resource.setFileName(file.getOriginalFilename());
        resource.setFilePath(relativePath);
        resource.setFileSize(file.getSize());
        resource.setContentType(file.getContentType());
        resource.setKind(Constants.RES_KIND_MATERIAL);
        resource.setUploader(UserContext.getUserId());
        resource.setDeleted(0);
        // file_url 为 NOT NULL：先落库拿到主键，再回填受控访问路径 /files/{id}/download
        resource.setFileUrl("");
        resourceMapper.insert(resource);
        resource.setFileUrl("/files/" + resource.getId() + "/download");
        resourceMapper.updateById(resource);
        return resource;
    }

    /** 绑定资料到课程/章节：先做课程归属校验 */
    @Transactional
    public void bind(Long courseId, ResourceBindRequest request) {
        Course course = courseSupport.requireOwned(courseId);
        validateKind(request.getKind());
        CourseResource resource = resourceMapper.selectById(request.getResourceId());
        if (resource == null || Integer.valueOf(1).equals(resource.getDeleted())) {
            throw new BizException("文件不存在");
        }
        if (resource.getCourseId() != null && !Objects.equals(resource.getCourseId(), courseId)) {
            throw new BizException("该文件已绑定到其他课程");
        }
        if (!CourseSupport.isBackstageAdmin()
                && !Objects.equals(resource.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "只能绑定本人上传的文件");
        }
        if (request.getChapterId() != null) {
            chapterService.requireChapterOfCourse(request.getChapterId(), courseId);
        }

        resource.setCourseId(courseId);
        resource.setChapterId(request.getChapterId());
        resource.setKind(request.getKind());
        resourceMapper.updateById(resource);

        if (Integer.valueOf(Constants.RES_KIND_COVER).equals(request.getKind())) {
            course.setCoverUrl(resource.getFileUrl());
            courseMapper.updateById(course);
        }
        teacherCourseService.evictCache(courseId);
    }

    /** M15.20-D：本人未绑定文件（course_id IS NULL 且 deleted=0；绑定失败恢复入口） */
    public List<ResourceVO> listUnbound(Long uploaderId) {
        return resourceMapper.selectList(
                        Wrappers.<CourseResource>lambdaQuery()
                                .eq(CourseResource::getUploader, uploaderId)
                                .isNull(CourseResource::getCourseId)
                                .eq(CourseResource::getDeleted, 0)
                                .orderByDesc(CourseResource::getCreateTime))
                .stream().map(ResourceService::toVO).toList();
    }

    public List<ResourceVO> list(Long courseId) {
        courseSupport.requireOwned(courseId);
        List<CourseResource> rows = resourceMapper.selectList(
                Wrappers.<CourseResource>lambdaQuery()
                        .eq(CourseResource::getCourseId, courseId)
                        .eq(CourseResource::getDeleted, 0)
                        .orderByAsc(CourseResource::getCreateTime));
        refreshKb(rows);
        return rows.stream().map(ResourceService::toVO).toList();
    }

    private void refreshKb(List<CourseResource> rows) {
        String ids = rows.stream()
                .filter(r -> r.getKbDocId() != null || Integer.valueOf(1).equals(r.getKbStatus()))
                .map(r -> String.valueOf(r.getId()))
                .reduce((a, b) -> a + "," + b).orElse("");
        if (ids.isEmpty()) {
            return;
        }
        try {
            var resp = aigcKnowledgeClient.sourceStatus(ids);
            if (resp == null || resp.getData() == null) {
                return;
            }
            java.util.Map<String, java.util.Map<String, Object>> byId = new java.util.HashMap<>();
            for (var item : resp.getData()) {
                if (item.get("resourceId") != null) {
                    byId.put(String.valueOf(item.get("resourceId")), item);
                }
            }
            for (CourseResource row : rows) {
                var item = byId.get(String.valueOf(row.getId()));
                if (item != null) {
                    applyKb(row, item);
                    resourceMapper.updateById(row);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[kb] 刷新资料状态失败，页面保留上次结果: {}", ex.getMessage());
        }
    }

    private static void applyKb(CourseResource row, java.util.Map<String, Object> item) {
        row.setKbStatus(1);
        if (item.get("docId") != null) {
            row.setKbDocId(Long.valueOf(String.valueOf(item.get("docId"))));
        }
        if (item.get("reviewStatus") instanceof Number n) {
            row.setKbReviewStatus(n.intValue());
        }
        if (item.get("indexStatus") instanceof Number n) {
            row.setKbIndexStatus(n.intValue());
        }
        row.setKbFailReason(item.get("failReason") == null ? null : String.valueOf(item.get("failReason")));
        row.setKbVersion(item.get("sourceVersion") == null ? String.valueOf(row.getId()) : String.valueOf(item.get("sourceVersion")));
    }

    public java.util.Map<String, Object> internalContent(Long resourceId) {
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || Integer.valueOf(1).equals(resource.getDeleted()) || resource.getCourseId() == null) {
            throw new BizException("课程资料不存在或未绑定课程");
        }
        Course course = courseMapper.selectById(resource.getCourseId());
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            throw new BizException("课程不存在");
        }
        Path path = fileStorageService.resolve(resource.getFilePath());
        if (!Files.exists(path)) {
            throw new BizException("文件已丢失，请联系管理员");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (java.io.IOException ex) {
            throw new BizException("读取课程资料失败");
        }
        boolean free = Integer.valueOf(1).equals(course.getFree())
                || course.getPrice() == null || course.getPrice() == 0;
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("resourceId", String.valueOf(resource.getId()));
        body.put("version", String.valueOf(resource.getId()));
        body.put("courseId", String.valueOf(course.getId()));
        body.put("free", free);
        body.put("fileName", resource.getFileName());
        body.put("contentType", resource.getContentType());
        body.put("uploaderId", resource.getUploader() == null ? null : String.valueOf(resource.getUploader()));
        body.put("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes));
        return body;
    }

    /**
     * M15.20-E：资料替换——上传新文件并顶替旧版本；旧资源保留历史链（replaced_by），可追溯。
     * 归属校验：只能替换本人上传的资源；视频替换保持原 kind/章节。
     */
    @Transactional
    public CourseResource replace(Long oldResourceId, MultipartFile file) {
        CourseResource old = resourceMapper.selectById(oldResourceId);
        if (old == null || Integer.valueOf(1).equals(old.getDeleted())) {
            throw new BizException("原资源不存在");
        }
        if (!CourseSupport.isBackstageAdmin() && !Objects.equals(old.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "只能替换本人上传的资源");
        }
        CourseResource fresh = upload(file);
        fresh.setCourseId(old.getCourseId());
        fresh.setChapterId(old.getChapterId());
        fresh.setKind(old.getKind());
        fresh.setFileUrl("/files/" + fresh.getId() + "/download");
        resourceMapper.updateById(fresh);
        // 旧资源标记被替换（保留行作历史证据；列表过滤 deleted/replaced 由 deleted=1 承载）
        old.setReplacedBy(fresh.getId());
        old.setReplaceTime(java.time.LocalDateTime.now());
        old.setDeleted(1);
        resourceMapper.updateById(old);
        withdrawKnowledge(old.getId());
        if (old.getCourseId() != null) {
            teacherCourseService.evictCache(old.getCourseId());
        }
        return fresh;
    }

    /** M15.20-E：历史版本链（从当前资源向前追溯被替换记录；仅本人可见） */
    public List<ResourceVO> history(Long resourceId) {
        CourseResource cur = resourceMapper.selectById(resourceId);
        if (cur == null) {
            throw new BizException("资源不存在");
        }
        if (!CourseSupport.isBackstageAdmin() && !Objects.equals(cur.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "无权查看该资源历史");
        }
        // 向前追溯：当前资源←被替换前的版本（replaced_by 指向关系反向遍历）
        java.util.List<ResourceVO> chain = new java.util.ArrayList<>();
        Long prevId = findPredecessor(resourceId);
        while (prevId != null) {
            CourseResource prev = resourceMapper.selectById(prevId);
            if (prev == null) break;
            chain.add(toVO(prev));
            prevId = findPredecessor(prev.getId());
        }
        return chain;
    }

    /** 查找 prev（谁的 replaced_by = curId） */
    private Long findPredecessor(Long curId) {
        var list = resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                .eq(CourseResource::getReplacedBy, curId).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0).getId();
    }

    /**
     * 加入知识库。远程调用不放在本地事务里。
     * 同一资源重复点击由 aigc 按资源 ID 找回原文档。
     */
    public ResourceVO submitKb(Long resourceId) {
        CourseResource r = resourceMapper.selectById(resourceId);
        if (r == null || Integer.valueOf(1).equals(r.getDeleted())) {
            throw new BizException("资源不存在");
        }
        if (r.getCourseId() == null) {
            throw new BizException("请先把资料绑定到课程，再加入知识库");
        }
        courseSupport.requireOwned(r.getCourseId());
        if (!CourseSupport.isBackstageAdmin() && !Objects.equals(r.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "只能提交本人上传的资源");
        }
        String name = r.getFileName() == null ? "" : r.getFileName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".pdf") || name.endsWith(".md") || name.endsWith(".markdown"))) {
            throw new BizException("当前只支持把 PDF 或 Markdown 加入知识库，其他格式仍可作课程附件");
        }
        com.kzai.common.result.R<java.util.Map<String, Object>> resp;
        try {
            resp = aigcKnowledgeClient.submitKb(java.util.Map.of("resourceId", String.valueOf(r.getId())));
        } catch (RuntimeException ex) {
            throw new BizException("知识库服务暂不可用，资料尚未进入审核：" + ex.getMessage());
        }
        if (resp == null || !Integer.valueOf(200).equals(resp.getCode()) || resp.getData() == null) {
            throw new BizException(resp == null || resp.getMsg() == null
                    ? "知识库审核任务创建失败，请稍后重试" : resp.getMsg());
        }
        applyKb(r, resp.getData());
        resourceMapper.updateById(r);
        return toVO(r);
    }

    /** 索引失败后的重试。服务端按资源 ID 找文档，不接受调用方传入的文档 ID。 */
    public java.util.Map<String, Object> retryKb(Long resourceId) {
        CourseResource r = requireOwnedResource(resourceId);
        if (!"FAILED".equals(kbPhase(r)) || r.getKbReviewStatus() == null || r.getKbReviewStatus() != 1) {
            throw new BizException("只有已审核且索引失败的资料可以重试");
        }
        com.kzai.common.result.R<java.util.Map<String, Object>> resp;
        try {
            resp = aigcKnowledgeClient.retry(java.util.Map.of("resourceId", String.valueOf(r.getId())));
        } catch (RuntimeException ex) {
            throw new BizException("知识库重试失败：" + ex.getMessage());
        }
        if (resp == null || !Integer.valueOf(200).equals(resp.getCode()) || resp.getData() == null) {
            throw new BizException(resp == null || resp.getMsg() == null ? "知识库重试失败" : resp.getMsg());
        }
        applyKb(r, resp.getData());
        if ("INDEXING".equals(String.valueOf(resp.getData().get("phase")))) {
            r.setKbIndexStatus(0);
        }
        resourceMapper.updateById(r);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jobId", resp.getData().get("jobId"));
        out.put("phase", "INDEXING");
        out.put("alreadyQueued", Boolean.TRUE.equals(resp.getData().get("alreadyQueued")));
        out.put("accessLevel", resp.getData().get("accessLevel"));
        return out;
    }

    private CourseResource requireOwnedResource(Long resourceId) {
        CourseResource r = resourceMapper.selectById(resourceId);
        if (r == null || Integer.valueOf(1).equals(r.getDeleted())) {
            throw new BizException("资源不存在或已替换");
        }
        if (r.getCourseId() == null) {
            throw new BizException("资料未绑定课程");
        }
        courseSupport.requireOwned(r.getCourseId());
        if (!CourseSupport.isBackstageAdmin() && !Objects.equals(r.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "只能操作本人上传的资源");
        }
        return r;
    }

    private void withdrawKnowledge(Long resourceId) {
        try {
            var resp = aigcKnowledgeClient.withdraw(java.util.Map.of("resourceId", String.valueOf(resourceId)));
            if (resp == null || !Integer.valueOf(200).equals(resp.getCode())) {
                throw new BizException(resp == null ? "撤回知识库来源失败" : resp.getMsg());
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BizException("知识库来源撤回失败，课程资料未删除：" + ex.getMessage());
        }
    }

    /** 解绑（逻辑删除）：封面解绑后清除课程封面 */
    @Transactional
    public void remove(Long resourceId) {
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || Integer.valueOf(1).equals(resource.getDeleted())) {
            throw new BizException("文件不存在");
        }
        if (resource.getCourseId() != null) {
            Course course = courseSupport.requireOwned(resource.getCourseId());
            boolean cover = Integer.valueOf(Constants.RES_KIND_COVER).equals(resource.getKind());
            resource.setDeleted(1);
            resourceMapper.updateById(resource);

            if (cover && Objects.equals(course.getCoverUrl(), resource.getFileUrl())) {
                course.setCoverUrl(null);
                courseMapper.updateById(course);
            }
            withdrawKnowledge(resource.getId());
            teacherCourseService.evictCache(course.getId());
            return;
        }
        if (!CourseSupport.isBackstageAdmin()
                && !Objects.equals(resource.getUploader(), UserContext.getUserId())) {
            throw new AuthException(ResultCode.FORBIDDEN, "无权删除他人上传的文件");
        }
        resource.setDeleted(1);
        resourceMapper.updateById(resource);
        withdrawKnowledge(resource.getId());
    }

    /** 下载访问控制：返回可读取的文件路径 */
    public Downloadable requireDownload(Long resourceId) {
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || Integer.valueOf(1).equals(resource.getDeleted())) {
            throw new BizException("文件不存在");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new AuthException(ResultCode.UNAUTHORIZED, "未登录");
        }
        // 免费公开资源 → 登录学员可读；付费受限资源 → 必须已取得学习权益（M4 起按已支付订单判定）
        boolean allowed = CourseSupport.isBackstageAdmin()
                || Objects.equals(resource.getUploader(), userId)
                || readableByStudent(resource, userId);
        if (!allowed) {
            throw new AuthException(ResultCode.FORBIDDEN, "无权访问该文件，请先购买课程");
        }
        Path path = fileStorageService.resolve(resource.getFilePath());
        if (!Files.exists(path)) {
            throw new BizException("文件已丢失，请联系管理员");
        }
        return new Downloadable(resource, path);
    }

    /**
     * 学员可读判定：课程已上架且分类启用，并且（免费课程 或 已取得付费学习权益）。
     * 未取得权益的学员不得通过资源 URL 直接下载付费课件/视频。
     */
    private boolean readableByStudent(CourseResource resource, Long userId) {
        if (resource.getCourseId() == null) {
            return false;
        }
        Course course = courseMapper.selectById(resource.getCourseId());
        if (course == null || Integer.valueOf(1).equals(course.getDeleted())) {
            return false;
        }
        if (!Integer.valueOf(Constants.COURSE_STATUS_ONLINE).equals(course.getStatus())) {
            return false;
        }
        if (!categoryService.categoryEnabled(course.getFirstCateId())
                || !categoryService.categoryEnabled(course.getSecondCateId())) {
            return false;
        }
        // 免费课程（price=0 / free=1）公开可读
        boolean free = Integer.valueOf(1).equals(course.getFree())
                || course.getPrice() == null
                || course.getPrice() == 0;
        if (free) {
            return true;
        }
        return entitlementProvider.hasPaidEntitlement(userId, course.getId());
    }

    private void validateKind(Integer kind) {
        if (kind == null || kind < Constants.RES_KIND_COVER || kind > Constants.RES_KIND_VIDEO) {
            throw new BizException("资源类型非法：1封面，2教学资料，3视频");
        }
    }

    public static ResourceVO toVO(CourseResource resource) {
        long size = resource.getFileSize() == null ? 0 : resource.getFileSize();
        return ResourceVO.builder()
                .id(resource.getId())
                .courseId(resource.getCourseId())
                .chapterId(resource.getChapterId())
                .kind(resource.getKind())
                .kindText(kindText(resource.getKind()))
                .fileName(resource.getFileName())
                .fileUrl(resource.getFileUrl())
                .fileSize(resource.getFileSize())
                .fileSizeText(size >= 1024 * 1024
                        ? String.format("%.2f MB", size / 1024.0 / 1024.0)
                        : String.format("%.1f KB", size / 1024.0))
                .contentType(resource.getContentType())
                .uploader(resource.getUploader())
                .createTime(resource.getCreateTime())
                .kbDocId(resource.getKbDocId())
                .kbReviewStatus(resource.getKbReviewStatus())
                .kbIndexStatus(resource.getKbIndexStatus())
                .kbFailReason(resource.getKbFailReason())
                .kbVersion(resource.getKbVersion())
                .kbPhase(kbPhase(resource))
                .kbRetryable("FAILED".equals(kbPhase(resource))
                        && resource.getKbReviewStatus() != null && resource.getKbReviewStatus() == 1)
                .build();
    }

    static String kbPhase(CourseResource resource) {
        if (resource.getKbDocId() == null && !Integer.valueOf(1).equals(resource.getKbStatus())) {
            return "NOT_SUBMITTED";
        }
        if (resource.getKbReviewStatus() != null && resource.getKbReviewStatus() == 2) {
            return "WITHDRAWN";
        }
        if (resource.getKbIndexStatus() != null && resource.getKbIndexStatus() == 5) {
            return "FAILED";
        }
        if (resource.getKbReviewStatus() == null || resource.getKbReviewStatus() == 0) {
            return "REVIEW_PENDING";
        }
        if (resource.getKbIndexStatus() != null && resource.getKbIndexStatus() == 4) {
            return "SEARCHABLE";
        }
        return "INDEXING";
    }

    private static String kindText(Integer kind) {
        if (kind == null) {
            return "未知";
        }
        return switch (kind) {
            case Constants.RES_KIND_COVER -> "封面";
            case Constants.RES_KIND_MATERIAL -> "教学资料";
            case Constants.RES_KIND_VIDEO -> "视频";
            default -> "未知";
        };
    }

    /** 下载结果：资源元数据 + 服务端真实路径 */
    public record Downloadable(CourseResource resource, Path path) {
    }
}
