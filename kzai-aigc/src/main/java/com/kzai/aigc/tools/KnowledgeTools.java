package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.chat.StopRegistry;
import com.kzai.aigc.knowledge.retrieval.KnowledgeRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 知识库检索工具（M9，只读）：仅授予 KNOWLEDGE Agent（AgentToolPolicy 矩阵）。
 * <p>
 * 职责：TopK6、阈值 0.6（归一化口径）的真实 ES kNN 检索 + 授权/状态过滤；
 * 结果写入 ToolResultHolder 作为 1003 RAG_SOURCE 来源卡片（与 assistant 消息持久化，
 * 历史回放无需重新检索）；ES 不可用返回受控降级，不假装完成 RAG。
 * 只读：绝不创建订单或修改课程；userId 一律来自 ToolContext 可信身份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    public static final String NAME_SEARCH = "searchKnowledge";
    public static final String CARD_RAG_SOURCE = "RAG_SOURCE";

    private final KnowledgeRetriever knowledgeRetriever;
    private final StopRegistry stopRegistry;
    private final ToolAudit audit;
    private final ObjectMapper objectMapper;

    public List<FunctionCallback> callbacks() {
        return List.of(FunctionCallback.builder()
                .function(NAME_SEARCH, (BiFunction<KnowledgeSearchRequest, ToolContext, String>) this::searchKnowledge)
                .description("检索平台知识库（课程介绍、FAQ、购买规则、讲义）。"
                        + "回答知识类问题前必须先调用本工具；只能引用工具返回的片段并标注来源，"
                        + "检索为空时如实告知未找到，禁止编造。")
                .inputType(KnowledgeSearchRequest.class)
                .build());
    }

    public static class KnowledgeSearchRequest {
        private String question;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }
    }

    /** 知识检索（模型回调入口；RAG_SOURCE 卡片写入本轮 ToolResultHolder） */
    public String searchKnowledge(KnowledgeSearchRequest request, ToolContext toolContext) {
        long t0 = System.currentTimeMillis();
        ToolResultHolder round = CourseTools.holderOf(toolContext);
        String requestId = CourseTools.strOf(toolContext, ToolContextKeys.REQUEST_ID);
        String sessionId = CourseTools.strOf(toolContext, ToolContextKeys.SESSION_ID);
        Long userId = CourseTools.userIdOf(toolContext);
        String question = request == null ? null : request.getQuestion();
        if (question == null || question.isBlank()) {
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_BAD_REQUEST);
            return json(Map.of("status", "BAD_REQUEST", "message", "缺少检索问题"));
        }
        if (userId == null) {
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_NO_IDENTITY);
            return json(Map.of("status", "NO_IDENTITY", "message", "缺少可信用户身份，已拒绝检索"));
        }
        if (sessionId != null && stopRegistry.isStopRequested(sessionId)) {
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, 0, ToolAudit.ERR_STOP_REQUESTED);
            return json(Map.of("status", "STOPPED", "message", "用户已停止生成，检索已终止"));
        }
        try {
            KnowledgeRetriever.RetrievalResult result =
                    knowledgeRetriever.retrieve(question, userId, rolesOf(toolContext), null);
            if (result.degraded) {
                audit.record(NAME_SEARCH, requestId, sessionId, since(t0), false, 0, result.degradeReason);
                String reason = "ES_UNAVAILABLE".equals(result.degradeReason)
                        ? "知识库服务暂时不可用，请如实告知用户稍后再试，不得编造知识内容"
                        : "检索暂时不可用，请如实告知用户";
                return json(Map.of("status", "DEGRADED", "degradeReason", result.degradeReason,
                        "message", reason));
            }
            // RAG_SOURCE 来源卡片（每片段一张，随 assistant 消息持久化，历史回放免检索）
            int cards = 0;
            for (Map<String, Object> hit : result.hits) {
                if (round != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("docId", hit.get("docId"));
                    payload.put("chunkId", hit.get("chunkId"));
                    payload.put("chunkIndex", hit.get("chunkIndex"));
                    payload.put("docTitle", hit.get("docTitle"));
                    payload.put("courseId", hit.get("courseId"));
                    payload.put("score", hit.get("score"));
                    payload.put("snippet", hit.get("snippet"));
                    payload.put("provider", hit.get("provider"));
                    if (round.addCard(CARD_RAG_SOURCE, "rag:" + hit.get("chunkId"), payload)) {
                        cards++;
                    }
                }
            }
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), true, result.hits.size(), null);
            List<Map<String, Object>> forModel = new ArrayList<>();
            for (Map<String, Object> hit : result.hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunkId", hit.get("chunkId"));
                m.put("docTitle", hit.get("docTitle"));
                m.put("courseId", hit.get("courseId"));
                m.put("score", hit.get("score"));
                m.put("text", hit.get("text"));
                forModel.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", result.hits.isEmpty() ? "NOT_FOUND" : "OK");
            body.put("count", result.hits.size());
            body.put("hits", forModel);
            if (result.hits.isEmpty()) {
                body.put("message", "知识库中没有找到与问题相关的片段。请如实告知用户未找到，禁止编造。");
            } else {
                body.put("note", "回答必须只基于 hits 中的片段，引用时标注来源文档名；片段之外的常识可用但不得标注来源");
            }
            return json(body);
        } catch (RuntimeException ex) {
            log.warn("[tool] searchKnowledge 异常: {}", ex.getMessage());
            audit.record(NAME_SEARCH, requestId, sessionId, since(t0), false, 0, ToolAudit.ERR_SERVICE_UNAVAILABLE);
            return json(Map.of("status", "KNOWLEDGE_SERVICE_UNAVAILABLE",
                    "message", "知识库服务暂时不可用，请如实告知用户稍后再试，不得编造内容"));
        } finally {
            com.kzai.common.context.UserContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return List.of();
        }
        Object roles = toolContext.getContext().get(ToolContextKeys.ROLES);
        return roles instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            return "{\"status\":\"ERROR\",\"message\":\"工具结果序列化失败\"}";
        }
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }

    /** 供控制器/脚本直接检索（复用同一授权链路） */
    public KnowledgeRetriever.RetrievalResult retrieveFor(long userId, List<String> roles, String question) {
        return knowledgeRetriever.retrieve(question, userId, roles, null);
    }
}
