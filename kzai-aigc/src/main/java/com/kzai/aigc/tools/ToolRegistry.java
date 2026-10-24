package com.kzai.aigc.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（M7 → M9）。
 * <p>
 * 聚合全部工具回调（Spring AI 1.0.0-M6 FunctionCallback）——回调必须在此注册，
 * 才能进入模型构造期 register（M8 缺陷②修复的装配前提）；
 * 并把<b>可信上下文</b>装配进 ToolContext：userId/roles 来自网关验证后的认证上下文，
 * 由 ChatService 写入；模型只能通过工具入参表达意图，无法伪造身份。
 * ToolContext 同时携带本轮 {@link ToolResultHolder} 实例——结果隔离按
 * userId+sessionId+轮次维度天然成立，不存在全局静态状态。
 */
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final CourseTools courseTools;
    private final OrderTools orderTools;
    private final KnowledgeTools knowledgeTools;
    private final com.kzai.aigc.recommendation.RecommendationTools recommendationTools;

    /** 全部可用工具回调（只读课程 x2 + 预下单 x1 + 知识检索 x1） */
    public List<FunctionCallback> callbacks() {
        List<FunctionCallback> all = new ArrayList<>(courseTools.callbacks());
        all.addAll(orderTools.callbacks());
        all.addAll(knowledgeTools.callbacks());
        all.addAll(recommendationTools.callbacks());
        return List.copyOf(all);
    }

    /** 模型正常结束却未查课时，仍先执行同一只读推荐工具；不模拟工具成功。 */
    public void recommendationFallback(Map<String,Object> ctx) {
        recommendationTools.recommend(new com.kzai.aigc.recommendation.RecommendationRequest(),new ToolContext(ctx));
    }

    /**
     * 装配可信身份与本轮上下文，不含完整 JWT；Round 内原文仅服务端参数校验使用。
     */
    public Map<String, Object> toolContext(long userId, List<String> roles,
                                           String requestId, String sessionId,
                                           ToolResultHolder round) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(ToolContextKeys.USER_ID, userId);
        ctx.put(ToolContextKeys.ROLES, roles == null ? List.of() : roles);
        ctx.put(ToolContextKeys.REQUEST_ID, requestId);
        ctx.put(ToolContextKeys.SESSION_ID, sessionId);
        ctx.put(ToolContextKeys.ROUND, round);
        return ctx;
    }

    /** 供测试/脚本直接构造（MOCK 工具脚本复用同一套上下文装配） */
    public ToolContext asToolContext(long userId, List<String> roles,
                                     String requestId, String sessionId, ToolResultHolder round) {
        return new ToolContext(toolContext(userId, roles, requestId, sessionId, round));
    }
}
