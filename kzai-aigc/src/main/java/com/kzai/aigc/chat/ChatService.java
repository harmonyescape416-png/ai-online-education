package com.kzai.aigc.chat;

import com.kzai.aigc.agent.AgentToolPolicy;
import com.kzai.aigc.agent.AgentTypeEnum;
import com.kzai.aigc.agent.RouteAgent;
import com.kzai.aigc.agent.RouteResult;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.memory.ChatMemoryKeys;
import com.kzai.aigc.memory.RedisChatMemory;
import com.kzai.aigc.prompt.AgentPromptSpec;
import com.kzai.aigc.prompt.PromptManager;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.provider.ProviderException;
import com.kzai.aigc.session.ChatSessionService;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.aigc.tools.MockToolScriptService;
import com.kzai.aigc.tools.PurchaseProposalStore;
import com.kzai.aigc.tools.ToolRegistry;
import com.kzai.aigc.tools.ToolResultHolder;
import com.kzai.common.context.UserContext;
import com.kzai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天核心服务（M6 → M7 工具调用 → M8 多智能体编排）。
 * <p>
 * M8 流程（原始需求 3.2.4.3 / 设计说明书 4.5）：
 * <pre>
 * tryAcquire 单飞 → 写用户消息（messageId 幂等）→ 购买提案确认预扫描
 *   → RouteAgent 非流式意图路由（独立临时上下文，【不写 Redis】= RecordOptimization 等价机制）
 *   → OTHER：寒暄/无关直接回复（无工具，不进专业 Agent）
 *   → 专业 Agent：加载自身 Prompt 快照 + 会话记忆 + 白名单工具 → 流式回答
 * → 1001 增量 → 1003 卡片（去重）→ 恰好 1 × 1002 终态（携带 agent 标识）
 * </pre>
 * 保持不变的 M6/M7 约束：同会话单飞（/chat 与 /chat/text 互斥）、停止生成只存已输出、
 * 卡片持久化与 1002 前发送、Redis 不可用显式告知、Prompt 快照在生成开始时读取一次
 * （运行中不因发布/回滚切换）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** OTHER（寒暄/无关）直接回复策略：无工具、不冒充专业 Agent */
    static final String DIRECT_SYSTEM_PROMPT =
            "你是「课栈智伴」在线教育平台 AI 助手。用户发来的是寒暄、闲聊或与平台课程、"
                    + "技术知识无关的问题，请友好、简短地直接回复；如涉及课程推荐、课程咨询或购买，"
                    + "可提示用户换一种问法（例如直接说出想学的方向）。不要编造课程或价格信息。";

    private final ChatProvider provider;
    private final RedisChatMemory memory;
    private final ChatSessionService sessionService;
    private final StopRegistry stopRegistry;
    private final AiProperties props;
    private final SseEvent sse;
    private final ToolRegistry toolRegistry;
    private final MockToolScriptService mockToolScript;
    private final PurchaseProposalStore proposalStore;
    private final RouteAgent routeAgent;
    private final AgentToolPolicy agentToolPolicy;
    private final PromptManager promptManager;
    private final com.kzai.aigc.config.DynamicAiParams dynamic;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final com.kzai.aigc.stats.ModelCallLogService callLog;
    private final com.kzai.aigc.prompt.PromptVersionService promptVersionService; // M15.20-A

    /** M13 每日 AI 调用限额（Redis 原子计数，服务端可信身份，跨请求并发安全）。
     *  Redis 不可用时跳过限额（fail-open，与 M6 降级语义一致——限额是可用性增强，
     *  不得因 Redis 故障阻断用户对话）；计数异常同样不阻断。 */
    private void checkDailyQuota(long userId) {
        try {
            String day = java.time.LocalDate.now().toString();
            String key = "AI:QUOTA:" + userId + ":" + day;
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, java.time.Duration.ofHours(48)); // 跨次日边界安全
            }
            int limit = dynamic.dailyLimit();
            if (used != null && used > limit) {
                throw new com.kzai.common.exception.BizException(
                        "今日 AI 调用次数已达上限（" + limit + " 次），请明日再试");
            }
        } catch (com.kzai.common.exception.BizException quotaExceeded) {
            throw quotaExceeded; // 真实超限：受控拒绝
        } catch (RuntimeException redisDown) {
            log.warn("[aigc.chat] 每日限额检查跳过（Redis 不可用，fail-open）: {}", redisDown.getMessage());
        }
    }

    private AiModelCallLogBuilder callLogBuilder(String requestId, long userId, String sessionId) {
        return new AiModelCallLogBuilder(requestId, userId, sessionId, provider, callLog);
    }

    /** 模型调用日志构建（M13，轮终态一次性写入） */
    private record AiModelCallLogBuilder(String requestId, long userId, String sessionId,
                                         ChatProvider provider,
                                         com.kzai.aigc.stats.ModelCallLogService callLog) {
        void chat(String agent, boolean ok, String errorKind, Integer firstTokenMs,
                  long totalMs, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            com.kzai.aigc.stats.entity.AiModelCallLog r = new com.kzai.aigc.stats.entity.AiModelCallLog();
            r.setRequestId(requestId);
            r.setUserId(userId);
            r.setSessionId(sessionId);
            r.setSource(com.kzai.aigc.stats.entity.AiModelCallLog.SOURCE_CHAT);
            r.setAgent(agent);
            r.setProvider(provider.code());
            r.setModelCode(provider.mock() ? "MOCK" : provider.code());
            r.setSuccess(ok ? 1 : 0);
            r.setErrorKind(errorKind);
            r.setFirstTokenMs(firstTokenMs);
            r.setTotalLatencyMs((int) totalMs);
            r.setPromptTokens(promptTokens);
            r.setCompletionTokens(completionTokens);
            r.setTotalTokens(totalTokens);
            callLog.record(r);
        }

        void route(String agent, boolean ok, String errorKind, long costMs) {
            com.kzai.aigc.stats.entity.AiModelCallLog r = new com.kzai.aigc.stats.entity.AiModelCallLog();
            r.setRequestId(requestId);
            r.setUserId(userId);
            r.setSessionId(sessionId);
            r.setSource(com.kzai.aigc.stats.entity.AiModelCallLog.SOURCE_ROUTE);
            r.setAgent(agent);
            r.setProvider(provider.code());
            r.setModelCode(provider.mock() ? "MOCK" : provider.code());
            r.setSuccess(ok ? 1 : 0);
            r.setErrorKind(errorKind);
            r.setRouteLatencyMs((int) costMs);
            callLog.record(r);
        }
    }

    /**
     * 流式对话（M8 多智能体编排）。
     * 事件顺序：N × 1001 → M × 1003（去重）→ 恰好 1 × 1002（含 agent 标识）。
     */

    /**
     * M15.20-A：会话版本感知的 Prompt 解析。
     * 会话创建时绑定了版本映射（prompt_version_map）→ 按 AgentType 读历史版本内容（不可变快照）；
     * 存量会话（映射为空/该类型无记录）→ 回退当前生效模板（兼容策略，见设计说明）。
     */
    private AgentPromptSpec resolveSpec(Long userId, String sessionId, AgentTypeEnum agentType) {
        var session = sessionService.requireOwned(userId, sessionId);
        if (session != null && session.getPromptVersionMap() != null
                && !session.getPromptVersionMap().isBlank()) {
            var spec = promptVersionService.specFromVersionMap(session.getPromptVersionMap(), agentType);
            if (spec != null) {
                return spec;
            }
        }
        return promptManager.get(agentType);
    }

    public void streamChat(long userId, String sessionId, String userText,
                           String messageId, jakarta.servlet.http.HttpServletResponse response) throws IOException {
            // M15.15：同会话连续重复文本防护（2 秒窗口，防双击/重试产生多条相同历史）
            if (memory.isDuplicateWithinWindow(userId, sessionId, userText, 2)) {
                // M15.15：以 END 事件返回受控错误码（前端 friendlyError 呈现）
                sse.write(response, SseEvent.TYPE_END, java.util.Map.of(
                        "terminal", true,
                        "code", SseEvent.TERM_ERROR,
                        "message", "请勿短时间内重复发送相同内容"));
                return;
            }
        // 1) 归属校验（防越权访问他人会话）
        ChatSession session = sessionService.requireOwned(userId, sessionId);

        // 2) 任务级单飞（/chat 与 /chat/text 互斥）
        if (!stopRegistry.tryAcquire(sessionId)) {
            sse.write(response, SseEvent.TYPE_END, Map.of(
                    "terminal", true,
                    "code", SseEvent.TERM_ERROR,
                    "message", "该会话已有进行中的生成任务，请稍候或先停止",
                    "mock", provider.mock(),
                    "provider", provider.code()));
            return;
        }
        stopRegistry.clearStop(sessionId);

        long t0 = System.nanoTime();
        String conversationId = ChatMemoryKeys.conversationId(userId, sessionId);
        StringBuilder assistant = new StringBuilder();
        boolean stopped = false;
        String terminalCode = SseEvent.TERM_OK;
        String terminalMessage = "ok";
        String requestId = UserContext.getRequestId();
        List<String> roles = UserContext.getRoles();
        ToolResultHolder round = new ToolResultHolder(userId, sessionId, requestId);
        String agentTag = null;
        int firstChunkMs = 0; // M13：提升到方法级（finally 调用日志使用）

        try {
            // 2.5) M13 每日限额（在 try 内：超限以受控终态返回，避免裸异常无 SSE 输出）
            checkDailyQuota(userId);

            // 3) 写用户消息到记忆（messageId 幂等）——路由不产生任何额外记忆
            boolean first = messageId == null || messageId.isBlank()
                    || memory.markOnce(messageId, Duration.ofHours(props.getMemoryTtlHours()));
            if (first) {
                memory.add(conversationId, List.of(new UserMessage(userText)));
            }

            // 4) 购买确认预扫描（先于路由：确认短语应命中 BUY 续轮）
            proposalConfirmation(userId, sessionId, userText);

            // 5) 会话记忆（标准消息；路由不写入）
            List<Message> history = memory.get(conversationId, 20);

            // 6) 模型流选择：M8 路由 → 分发；关闭开关时保持 M7 行为
            Map<String, Object> toolCtx = toolRegistry.toolContext(userId, roles, requestId, sessionId, round);
            Flux<String> stream = null;
            if (props.isMultiAgentEnabled()) {
                // 路由使用独立临时上下文（RouteAgent 内部不触碰 RedisChatMemory）
                RouteResult route = routeAgent.route(userId, sessionId, userText,
                        resolveSpec(userId, sessionId, AgentTypeEnum.ROUTE));
                // M13：路由模型调用日志（MOCK 路由脚本也如实记录 provider=MOCK）
                callLogBuilder(requestId, userId, sessionId).route(
                        route.isDirectAnswer() ? "OTHER" : (route.getAgent() == null ? null : route.getAgent().name()),
                        !route.isFallback(), route.getErrorKind(), route.getCostMs());
                if (route.isDirectAnswer()) {
                    agentTag = "OTHER";
                    stream = directAnswerStream(history);
                } else {
                    AgentTypeEnum agent = route.getAgent();
                    agentTag = agent.name();
                    stream = streamFor(userId, sessionId, agent, history, toolCtx, round, userText);
                }
            } else {
                stream = legacyStream(userText, history, toolCtx, round, userId, sessionId);
            }
            if (stream == null) {
                stream = provider.stream(history);
            }

            // 7) 停止守卫：takeWhile 命中后向上游传播取消
            Flux<String> guarded = stream.takeWhile(chunk -> !stopRegistry.isStopRequested(sessionId));
            int chunks = 0;
            for (String chunk : guarded.toIterable()) {
                if (chunk == null) {
                    continue;
                }
                assistant.append(chunk);
                chunks++;
                if (chunks == 1) {
                    firstChunkMs = firstChunkMs(t0);
                }
                sse.write(response, SseEvent.TYPE_DELTA, Map.of("content", chunk));
            }

            stopped = stopRegistry.isStopRequested(sessionId);
            if (stopped) {
                terminalCode = SseEvent.TERM_STOPPED;
                terminalMessage = "已停止生成，仅保留已输出内容";
            }
            if (firstChunkMs > 0) {
                log.info("[aigc.chat] agent={} firstChunkMs={} chunks={} mock={} provider={} cards={} promptTokens={} completionTokens={} totalTokens={}",
                        agentTag, firstChunkMs, chunks, provider.mock(), provider.code(), round.cards().size(),
                        round.getPromptTokens(), round.getCompletionTokens(), round.getTotalTokens());
            }

            // 8) 助手消息 + 工具卡片持久化（停止只写已输出；历史不含任何路由中间消息）
            //    M13：附带本轮 agent 标识（监管按意图筛选依据）
            memory.appendAssistant(conversationId, assistant.toString(), round.cards(), agentTag);

            if (memory.isDegraded(conversationId)) {
                terminalMessage = "已生成，但会话存储（Redis）不可用，本次对话为临时内容，未可靠保存";
            }

            // 9) 首问自动标题（条件更新，防覆盖重命名）
            if (session.getTitle() == null || session.getTitle().isBlank()) {
                String firstUser = extractFirstUserText(history, userText);
                try {
                    sessionService.tryAutoTitle(userId, sessionId, firstUser);
                } catch (RuntimeException ex) {
                    log.warn("tryAutoTitle 失败：{}", ex.getMessage());
                }
            }
        } catch (ProviderException ex) {
            terminalCode = mapProviderError(ex);
            terminalMessage = ex.getMessage();
            log.warn("ProviderException provider={} kind={} msg={}", ex.getProvider(), ex.getKind(), ex.getMessage());
        } catch (RuntimeException ex) {
            terminalCode = SseEvent.TERM_ERROR;
            terminalMessage = "服务异常：" + ex.getMessage();
            log.warn("streamChat 异常", ex);
        } finally {
            // 10) 1003 卡片：去重后输出，必须在唯一 1002 之前
            try {
                for (Map<String, Object> card : round.cards()) {
                    sse.write(response, SseEvent.TYPE_PARAM, card);
                }
            } catch (IOException ex) {
                log.warn("1003 卡片输出失败（不影响终态）: {}", ex.getMessage());
            }
            // 11) 终态：恰好一个；含 agent 标识与 usage；contentLength 代替全文
            Map<String, Object> terminal = new LinkedHashMap<>();
            terminal.put("terminal", true);
            terminal.put("code", terminalCode);
            terminal.put("message", terminalMessage);
            terminal.put("mock", provider.mock());
            terminal.put("provider", provider.code());
            terminal.put("stopped", stopped);
            terminal.put("persisted", !memory.isDegraded(conversationId));
            terminal.put("contentLength", assistant.length());
            terminal.put("cardCount", round.cards().size());
            if("RECOMMEND".equals(agentTag))terminal.put("recommendationServerFallback",round.usedRecommendationFallback());
            if (agentTag != null) {
                terminal.put("agent", agentTag);
            }
            if (round.getTotalTokens() != null) {
                terminal.put("promptTokens", round.getPromptTokens());
                terminal.put("completionTokens", round.getCompletionTokens());
                terminal.put("totalTokens", round.getTotalTokens());
            }
            sse.write(response, SseEvent.TYPE_END, terminal);
            stopRegistry.release(sessionId);
            stopRegistry.clearStop(sessionId);
            // M13：业务请求轮次调用日志（每轮恰好一条；source=CHAT；成功=正常终态）
            callLogBuilder(requestId, userId, sessionId).chat(
                    agentTag,
                    SseEvent.TERM_OK.equals(terminalCode) || SseEvent.TERM_STOPPED.equals(terminalCode),
                    SseEvent.TERM_OK.equals(terminalCode) ? null : terminalCode,
                    firstChunkMs > 0 ? firstChunkMs : null,
                    (System.nanoTime() - t0) / 1_000_000,
                    round.getPromptTokens(), round.getCompletionTokens(), round.getTotalTokens());
        }
    }

    /**
     * 非流式文本对话（M10 课程问答自动回复基础）：不走路由，保持 M7 行为。
     * 与 /chat 共享同会话单飞锁。
     */
    public String callChat(long userId, String sessionId, String userText, String messageId) {
        // M15.15：同会话连续重复文本防护
        if (memory.isDuplicateWithinWindow(userId, sessionId, userText, 2)) {
            throw new com.kzai.common.exception.BizException("请勿短时间内重复发送相同内容");
        }
        ChatSession session = sessionService.requireOwned(userId, sessionId);
        checkDailyQuota(userId); // M13 每日限额同样约束非流式问答链路
        if (!stopRegistry.tryAcquire(sessionId)) {
            throw new BizException("该会话已有进行中的生成任务，请稍候或先停止");
        }
        String conversationId = ChatMemoryKeys.conversationId(userId, sessionId);
        long t0 = System.nanoTime();
        String requestId = UserContext.getRequestId();
        try {
            boolean first = messageId == null || messageId.isBlank()
                    || memory.markOnce(messageId, Duration.ofHours(props.getMemoryTtlHours()));
            if (first) {
                memory.add(conversationId, List.of(new UserMessage(userText)));
            }
            List<Message> history = buildModelMessages(
                    resolveSpec(userId, sessionId, AgentTypeEnum.CONSULT), memory.get(conversationId, 20));
            String reply;
            try {
                reply = provider.call(history);
            } catch (ProviderException ex) {
                callLogBuilder(requestId, userId, sessionId).chat(
                        "CONSULT", false, ex.getKind() == null ? "PROVIDER_ERROR" : ex.getKind().name(),
                        null, (System.nanoTime() - t0) / 1_000_000, null, null, null);
                throw ex;
            }
            memory.appendAssistant(conversationId, reply, null, "CONSULT"); // M13：携带意图元数据
            callLogBuilder(requestId, userId, sessionId).chat(
                    "CONSULT", true, null, null,
                    (System.nanoTime() - t0) / 1_000_000,
                    null, null, null);
            if (session.getTitle() == null || session.getTitle().isBlank()) {
                try {
                    sessionService.tryAutoTitle(userId, sessionId,
                            extractFirstUserText(history, userText));
                } catch (RuntimeException ignore) {
                    // 标题失败不影响回答
                }
            }
            return reply;
        } finally {
            stopRegistry.release(sessionId);
            stopRegistry.clearStop(sessionId);
        }
    }

    // ------------------------------------------------------------------
    // M8 分发
    // ------------------------------------------------------------------

    /** 专业 Agent 流：Prompt 快照 + 记忆 + 白名单工具（最小授权） */
    private Flux<String> streamFor(Long userId, String sessionId, AgentTypeEnum agent, List<Message> history,
                                   Map<String, Object> toolCtx, ToolResultHolder round, String userText) {
        AgentPromptSpec spec = resolveSpec(userId, sessionId, agent);
        List<FunctionCallback> whitelist = agentToolPolicy.callbacksFor(agent);
        List<Message> messages = buildModelMessages(spec, history);
        if (agent == AgentTypeEnum.RECOMMEND) {
            round.beginRecommendationTurn(userText);
            messages.set(0,new SystemMessage(messages.get(0).getText()+"\n"
                    +com.kzai.aigc.recommendation.RecommendationReplyPolicy.SYSTEM_RULES));
        }
        if (provider.mock()) {
            // MOCK：脚本化工具流（真实工具实现），分支跟随路由结果；
            // KNOWLEDGE/未命中脚本 → MOCK plain
            Flux<String> scripted = mockToolScript.tryStream(userText, new ToolContext(toolCtx),
                    round.getUserId(), round.getSessionId(), agent);
            return scripted != null ? scripted : provider.stream(messages);
        }
        if (whitelist.isEmpty() && agent!=AgentTypeEnum.RECOMMEND) {
            return provider.stream(messages);
        }
        Flux<String> execution=whitelist.isEmpty()?provider.stream(messages):provider.streamWithTools(messages, whitelist, toolCtx, round);
        if(agent!=AgentTypeEnum.RECOMMEND)return execution;
        // 读取完整流以保留正常 token 统计，展示只由工具结果负责，不转发模型自述。
        return execution.takeWhile(chunk -> !stopRegistry.isStopRequested(sessionId))
                .onErrorResume(error -> round.recommendationResult()!=null?Flux.empty():Flux.error(error))
                .thenMany(Flux.defer(() -> {
                    if(stopRegistry.isStopRequested(sessionId))return Flux.empty();
                    if(round.recommendationResult()==null){round.recommendationFallbackUsed();toolRegistry.recommendationFallback(toolCtx);}
                    return Flux.just(com.kzai.aigc.recommendation.RecommendationReplyPolicy.render(round.recommendationResult()));
                }));
    }

    /** OTHER（寒暄/无关）直接回复：无工具、无专业 Agent 标签 */
    private Flux<String> directAnswerStream(List<Message> history) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        messages.add(new SystemMessage(DIRECT_SYSTEM_PROMPT));
        messages.addAll(history);
        return provider.stream(messages);
    }

    /** M7 兼容模式（multi-agent 关闭时）：单 Agent 全量工具 */
    private Flux<String> legacyStream(String userText, List<Message> history,
                                      Map<String, Object> toolCtx, ToolResultHolder round,
                                      long userId, String sessionId) {
        List<Message> messages = buildModelMessages(resolveSpec(userId, sessionId, AgentTypeEnum.CONSULT), history);
        if (provider.supportsTools()) {
            return provider.streamWithTools(messages, toolRegistry.callbacks(), toolCtx, round);
        }
        if (provider.mock() && props.isMockToolScript()) {
            Flux<String> scripted = mockToolScript.tryStream(userText, new ToolContext(toolCtx),
                    userId, sessionId, null);
            if (scripted != null) {
                return scripted;
            }
        }
        return provider.stream(history);
    }

    /** 购买确认预扫描：仅同会话存在待确认提案、且用户新消息是严格确认短语时才标记确认 */
    private void proposalConfirmation(long userId, String sessionId, String userText) {
        try {
            if (com.kzai.aigc.tools.MockToolScriptService.isStrictConfirmation(userText)) {
                boolean marked = proposalStore.markConfirmed(userId, sessionId);
                if (marked) {
                    log.info("[aigc.order] 购买提案已确认 userId={} sessionId={}", userId, sessionId);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("购买确认预扫描失败（按未确认处理）: {}", ex.getMessage());
        }
    }

    /** 模型输入 = Agent Prompt 快照 + Redis 历史；路由/系统消息不入库 */
    private List<Message> buildModelMessages(AgentPromptSpec spec, List<Message> history) {
        List<Message> out = new ArrayList<>(history.size() + 1);
        String system = spec != null && spec.getSystemPrompt() != null && !spec.getSystemPrompt().isBlank()
                ? spec.getSystemPrompt()
                : PromptManager.builtins().get(AgentTypeEnum.CONSULT).getSystemPrompt();
        out.add(new SystemMessage(system));
        out.addAll(history);
        return out;
    }

    private int firstChunkMs(long t0) {
        return (int) Duration.ofNanos(System.nanoTime() - t0).toMillis();
    }

    private String extractFirstUserText(List<Message> history, String current) {
        for (Message m : history) {
            if ("USER".equalsIgnoreCase(m.getMessageType().name())) {
                return m.getText();
            }
        }
        return current;
    }

    private String mapProviderError(ProviderException ex) {
        return switch (ex.getKind()) {
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case RATE_LIMIT -> "RATE_LIMIT";
            case TIMEOUT -> "TIMEOUT";
            case UPSTREAM -> SseEvent.TERM_UPSTREAM;
            default -> SseEvent.TERM_ERROR;
        };
    }
}
