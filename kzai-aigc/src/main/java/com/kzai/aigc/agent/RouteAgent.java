package com.kzai.aigc.agent;

import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.prompt.AgentPromptSpec;
import com.kzai.aigc.prompt.PromptManager;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.provider.ProviderException;
import com.kzai.aigc.tools.PurchaseProposalStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由智能体（M8，原始需求 3.2.4.3 / 设计说明书 4.5）。
 * <p>
 * 规则（全部落实）：
 * <ol>
 *   <li>路由先于专业 Agent 执行，使用<b>非流式</b>模型响应做意图判断；</li>
 *   <li>输出进行<b>严格枚举校验</b>（AgentTypeEnum.parseIntent），不用关键词匹配冒充真实 AI 路由
 *       ——MOCK 模式下的 {@link MockRouteScript} 仅用于协议验证并显著标识；</li>
 *   <li>无法解析时兜底 ConsultAgent；寒暄/无关问题（OTHER）直接回复；</li>
 *   <li>RouteAgent <b>无任何业务工具</b>、不得创建预订单、不改变用户身份与角色；</li>
 *   <li>路由调用<b>不写会话记忆</b>（RecordOptimization 等价机制：本类使用独立临时消息列表，
 *       不触碰 RedisChatMemory，路由失败也不留多余消息）；</li>
 *   <li>模型超时/失败/服务不可用受控降级（兜底 CONSULT，不阻塞用户输入）；</li>
 *   <li>记录路由意图、耗时与错误类型（ROUTE_AUDIT），不记录用户正文。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteAgent {

    private static final org.slf4j.Logger AUDIT = org.slf4j.LoggerFactory.getLogger("ROUTE_AUDIT");

    private final ChatProvider provider;
    private final PromptManager promptManager;
    private final PurchaseProposalStore proposalStore;
    private final AiProperties props;

    /**
     * 意图路由。
     *
     * @param userId    可信用户 ID（仅用于提案确认检查，绝不改变身份）
     * @param sessionId 会话 ID（路由状态不跨会话污染；提案确认按会话隔离）
     * @param userText  用户问题
     */
    public RouteResult route(long userId, String sessionId, String userText, AgentPromptSpec boundSpec) {
        long t0 = System.currentTimeMillis();
        // MOCK 模式：显著标识的脚本化路由（仅协议验证，不计入真实意图识别准确率）
        if (provider.mock()) {
            RouteResult r = MockRouteScript.classify(userText,
                    proposalStore.get(userId, sessionId));
            audit(r, since(t0), null);
            return r;
        }
        try {
            AgentPromptSpec spec = boundSpec != null ? boundSpec : promptManager.get(AgentTypeEnum.ROUTE);
            List<Message> messages = List.of(
                    new SystemMessage(spec.getSystemPrompt()+"\n用户调整选课预算、学习基础、每周时间、学习周期或要求重新推荐时属于 RECOMMEND；查询单门课价格仍属于 CONSULT。"),
                    new UserMessage(userText));
            // 非流式调用（无工具）：provider.call 只使用传入的临时消息列表，不触碰 Redis
            String raw = provider.call(messages);
            AgentTypeEnum intent = AgentTypeEnum.parseIntent(raw);
            if (intent == null) {
                // 无法解析：寒暄/无关 → 直接回复；其余兜底 CONSULT
                boolean greetingLike = raw != null
                        && (raw.toUpperCase().contains("OTHER") || raw.toUpperCase().contains("GREETING"));
                RouteResult r = greetingLike
                        ? RouteResult.direct(since(t0), raw)
                        : RouteResult.fallback(since(t0), "UNPARSEABLE");
                audit(r, since(t0), null);
                return r;
            }
            RouteResult r = RouteResult.ofAgent(intent, raw, since(t0));
            audit(r, since(t0), null);
            return r;
        } catch (ProviderException ex) {
            RouteResult r = RouteResult.fallback(since(t0),
                    ex.getKind() == ProviderException.Kind.TIMEOUT ? "TIMEOUT" : "ROUTE_MODEL_ERROR");
            audit(r, since(t0), ex.getMessage());
            return r;
        } catch (RuntimeException ex) {
            // 受控降级：路由失败绝不阻塞用户，兜底 CONSULT
            RouteResult r = RouteResult.fallback(since(t0), "ROUTE_ERROR");
            audit(r, since(t0), ex.getMessage());
            return r;
        }
    }

    private void audit(RouteResult r, long costMs, String error) {
        // 只记录意图与指标，不记录用户正文
        AUDIT.info("route={} fallback={} direct={} costMs={} provider={} errorKind={} raw={}",
                r.getAgent() == null ? "OTHER" : r.getAgent().name(),
                r.isFallback(), r.isDirectAnswer(), costMs, provider.code(),
                error == null ? "null" : error, r.getRawIntent());
        if (props.isRouteAuditVerbose() && error != null) {
            log.warn("[route] 路由失败详情: {}", error);
        }
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
