package com.kzai.aigc.agent;

import com.kzai.aigc.tools.CourseTools;
import com.kzai.aigc.tools.KnowledgeTools;
import com.kzai.aigc.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具权限矩阵（M8→M9，最小授权）。
 * <p>
 * <pre>
 * ┌────────────┬──────────────┬─────────────────┬────────────────┬──────────────────────┐
 * │ Agent      │ searchCourses│ getCourseDetail │ createPreOrder │ searchKnowledge      │
 * ├────────────┼──────────────┼─────────────────┼────────────────┼──────────────────────┤
 * │ ROUTE      │ ✗            │ ✗               │ ✗              │ ✗（无任何业务工具）    │
 * │ RECOMMEND  │ ✗            │ ✓               │ ✗              │ ✗                    │
 * │ CONSULT    │ ✓            │ ✓               │ ✗              │ ✗                    │
 * │ BUY        │ ✓            │ ✓               │ ✓（确认门禁）   │ ✗                    │
 * │ KNOWLEDGE  │ ✗            │ ✗               │ ✗              │ ✓（M9 只读检索）      │
 * └────────────┴──────────────┴─────────────────┴────────────────┴──────────────────────┘
 * </pre>
 * RECOMMEND 另有只读 recommendCourses，内部完成语义和关键词召回；不直接暴露旧关键词工具，
 * 避免模型绕过推荐条件、已购排除和降级标识。
 * 白名单在运行期 options 中按 Agent 装配（模型只见白名单内 schema）；
 * Prompt 模板不能修改白名单；即便模型越权调用 createPreOrder，
 * 服务端确认门禁（PurchaseProposalStore）仍会拦截未确认写操作（双层防护）。
 */
@Component
@RequiredArgsConstructor
public class AgentToolPolicy {

    private final CourseTools courseTools;
    private final OrderTools orderTools;
    private final KnowledgeTools knowledgeTools;
    private final com.kzai.aigc.recommendation.RecommendationTools recommendationTools;

    /** 返回该 Agent 允许的工具回调（白名单子集；名字必须已注册进模型 register） */
    public List<FunctionCallback> callbacksFor(AgentTypeEnum agent) {
        List<FunctionCallback> out = new ArrayList<>();
        if (agent == null) {
            return out;
        }
        switch (agent) {
            case RECOMMEND -> {
                out.addAll(recommendationTools.callbacks());
                out.addAll(courseTools.callbacks().stream().filter(c -> "getCourseDetail".equals(c.getName())).toList());
            }
            case CONSULT -> out.addAll(courseTools.callbacks());
            case BUY -> {
                out.addAll(courseTools.callbacks());
                out.addAll(orderTools.callbacks());
            }
            case KNOWLEDGE -> out.addAll(knowledgeTools.callbacks());
            default -> {
                // ROUTE：无任何业务工具
            }
        }
        return List.copyOf(out);
    }
}
