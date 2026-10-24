package com.kzai.aigc.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.KnowledgeAdminService;
import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import com.kzai.aigc.knowledge.retrieval.KnowledgeRetriever;
import com.kzai.aigc.tools.KnowledgeTools;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（M9，管理员专用）。
 * 全部走真实文件上传 / MySQL / ES；权限由 @RequireRoles(ADMIN) 服务端强制。
 */
@RestController
@RequestMapping("/admin/ai/knowledge")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_ADMIN})
public class KnowledgeAdminController {

    private final KnowledgeAdminService adminService;
    private final KnowledgeRetriever retriever;
    private final AiProperties props;
    private final com.kzai.aigc.knowledge.mapper.KnowledgeIndexJobMapper jobMapper;

    /** 上传文档（multipart：file + title + courseId? + accessLevel?） */
    @PostMapping("/documents")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "title", required = false) String title,
                                         @RequestParam(value = "courseId", required = false) Long courseId,
                                         @RequestParam(value = "accessLevel", required = false) String accessLevel) {
        long operator = requireAdmin();
        KnowledgeDocument doc = adminService.upload(file, title, courseId, accessLevel, operator);
        return R.ok(docMap(doc)).requestId(UserContext.getRequestId());
    }

    /** 文档列表（分页 + 关键词 + 状态筛选） */
    @GetMapping("/documents")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        Page<KnowledgeDocument> p = adminService.list(page, size, keyword, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", p.getTotal());
        data.put("pages", p.getPages());
        data.put("records", p.getRecords().stream().map(KnowledgeAdminController::docMap).toList());
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    @GetMapping("/documents/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(docMap(adminService.detail(id))).requestId(UserContext.getRequestId());
    }

    /** 审核发布 / 撤回（body: {publish: true|false}）；发布触发解析索引，撤回立即不可召回 */
    @PostMapping("/documents/{id}/review")
    public R<Map<String, Object>> review(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        boolean publish = !Boolean.FALSE.equals(body.get("publish"));
        KnowledgeDocument doc = adminService.review(id, publish, requireAdmin());
        return R.ok(docMap(doc)).requestId(UserContext.getRequestId());
    }

    /** 重新解析 */
    @PostMapping("/documents/{id}/reparse")
    public R<Map<String, Object>> reparse(@PathVariable Long id) {
        adminService.reparse(id, requireAdmin());
        return R.ok(Map.<String, Object>of("queued", true)).requestId(UserContext.getRequestId());
    }

    /** 重建索引 */
    @PostMapping("/documents/{id}/reindex")
    public R<Map<String, Object>> reindex(@PathVariable Long id) {
        adminService.reindex(id, requireAdmin());
        return R.ok(Map.<String, Object>of("queued", true)).requestId(UserContext.getRequestId());
    }

    /** 删除文档（逻辑删除 + ES 向量清理任务） */
    @DeleteMapping("/documents/{id}")
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        adminService.delete(id, requireAdmin());
        return R.ok(Map.<String, Object>of("deleted", true)).requestId(UserContext.getRequestId());
    }

    /** 任务状态查询 */
    @GetMapping("/jobs/{id}")
    public R<com.kzai.aigc.knowledge.entity.KnowledgeIndexJob> job(@PathVariable Long id) {
        return R.ok(jobMapper.selectById(id)).requestId(UserContext.getRequestId());
    }

    /** 检索测试（管理员）：真实 Embedding + ES TopK + 阈值 + 授权过滤 */
    @PostMapping("/search-test")
    public R<Map<String, Object>> searchTest(@RequestBody Map<String, Object> body) {
        long admin = requireAdmin();
        String question = strOf(body, "question");
        Integer topK = body.get("topK") == null ? null : ((Number) body.get("topK")).intValue();
        KnowledgeRetriever.RetrievalResult result =
                retriever.retrieve(question, admin, List.of(Constants.ROLE_ADMIN), topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("degraded", result.degraded);
        data.put("degradeReason", result.degradeReason);
        data.put("provider", "见各命中 provider 字段");
        data.put("topK", topK == null ? props.getKnowledgeTopK() : Math.max(1, Math.min(topK, 20)));
        data.put("threshold", props.getKnowledgeMinScore());
        data.put("hits", result.hits);
        if (result.degraded) {
            data.put("message", "检索降级：" + result.degradeReason);
        } else if (result.hits.isEmpty()) {
            data.put("message", "知识库中没有与问题相关的片段（未达到阈值 " + props.getKnowledgeMinScore() + "）");
        }
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    // ------------------------------------------------------------------

    private static long requireAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new com.kzai.common.exception.AuthException(
                    com.kzai.common.result.ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        return userId;
    }

    private static String strOf(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> docMap(KnowledgeDocument doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(doc.getId()));
        m.put("title", doc.getTitle());
        m.put("docType", doc.getDocType());
        m.put("courseId", doc.getCourseId() == null ? null : String.valueOf(doc.getCourseId()));
        m.put("fileName", doc.getFileName());
        m.put("fileSize", doc.getFileSize());
        m.put("charCount", doc.getCharCount());
        m.put("chunkCount", doc.getChunkCount());
        m.put("indexVersion", doc.getIndexVersion());
        m.put("indexStatus", doc.getIndexStatus());
        m.put("failReason", doc.getFailReason());
        m.put("accessLevel", doc.getAccessLevel());
        m.put("reviewStatus", doc.getReviewStatus());
        m.put("createTime", doc.getCreateTime() == null ? null : doc.getCreateTime().toString());
        return m;
    }
}
