package com.kzai.aigc.controller;

import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.knowledge.KnowledgeAdminService;
import com.kzai.aigc.knowledge.retrieval.KnowledgeRetriever;
import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量接口（M9，原始设计 5.2.3：POST /embedding 写入向量库、GET /embedding/search 向量检索）。
 * <p>
 * POST /embedding：{docId} 触发该文档重建向量索引（管理员；等价 reindex，真实链路复用任务补偿）。
 * GET /embedding/search：以当前可信用户上下文检索（TopK6/阈值0.6/授权过滤），
 * ES 不可用返回 degraded 明确降级状态；不泄露无权文档。
 */
@RestController
@RequestMapping("/embedding")
@RequiredArgsConstructor
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_TEACHER, Constants.ROLE_STAFF, Constants.ROLE_ADMIN})
public class EmbeddingController {

    private final KnowledgeRetriever retriever;
    private final KnowledgeAdminService adminService;
    private final AiProperties props;

    /** 向量写入（管理员）：触发文档重建索引（真实解析/切片/Embedding/ES 链路） */
    @PostMapping
    @RequireRoles({Constants.ROLE_ADMIN})
    public R<Map<String, Object>> write(@RequestBody Map<String, Object> body) {
        Long docId = body.get("docId") == null ? null : Long.valueOf(String.valueOf(body.get("docId")));
        if (docId == null) {
            throw new BizException("docId 必填");
        }
        adminService.reindex(docId, UserContext.getUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queued", true);
        data.put("docId", String.valueOf(docId));
        data.put("message", "已加入索引队列，任务按状态机异步执行（成功前旧版本索引继续可用）");
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    /** 向量检索：真实 ES kNN + 授权过滤；结果与 KnowledgeAgent 检索同链路 */
    @GetMapping("/search")
    public R<Map<String, Object>> search(@RequestBody(required = false) Map<String, Object> body) {
        return doSearch(body);
    }

    /** 兼容 query 参数形式（无 body） */
    @GetMapping(path = "/search", params = "question")
    public R<Map<String, Object>> searchByQuery(@org.springframework.web.bind.annotation.RequestParam("question") String question,
                                                @org.springframework.web.bind.annotation.RequestParam(value = "topK", required = false) Integer topK) {
        return doSearch(Map.of("question", question, "topK", topK == null ? 0 : topK));
    }

    private R<Map<String, Object>> doSearch(Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new com.kzai.common.exception.AuthException(
                    com.kzai.common.result.ResultCode.UNAUTHORIZED, "未登录或 token 无效");
        }
        String question = strOf(body, "question");
        Integer topK = body.get("topK") == null ? null : ((Number) body.get("topK")).intValue();
        KnowledgeRetriever.RetrievalResult result =
                retriever.retrieve(question, userId, UserContext.getRoles(), topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("degraded", result.degraded);
        data.put("degradeReason", result.degradeReason);
        data.put("topK", topK == null ? props.getKnowledgeTopK() : Math.max(1, Math.min(topK, 20)));
        data.put("threshold", props.getKnowledgeMinScore());
        data.put("hits", result.hits);
        if (result.degraded) {
            data.put("message", "知识库暂时不可用（" + result.degradeReason + "），已降级，未执行检索");
        } else if (result.hits.isEmpty()) {
            data.put("message", "知识库中没有与问题相关的片段");
        }
        return R.ok(data).requestId(UserContext.getRequestId());
    }

    private static String strOf(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
