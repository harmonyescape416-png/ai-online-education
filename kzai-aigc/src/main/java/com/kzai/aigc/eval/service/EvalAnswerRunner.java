package com.kzai.aigc.eval.service;

import com.kzai.aigc.agent.AgentTypeEnum;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.eval.DryRunPreOrderCallback;
import com.kzai.aigc.eval.entity.AiEvalResult;
import com.kzai.aigc.prompt.AgentPromptSpec;
import com.kzai.aigc.prompt.PromptManager;
import com.kzai.aigc.provider.ChatProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 评测回答执行器（M12）：与真实对话严格隔离。
 * <ul>
 *   <li>复用 PromptManager 生效模板快照 + ChatProvider；<b>不触碰</b> RedisChatMemory、
 *       会话、StopRegistry、通知 Outbox、业务表；</li>
 *   <li>RAG 上下文以「检索片段 + 文档标题」注入系统提示词（与真实 KNOWLEDGE 链路同源数据，
 *       检索效果由 KnowledgeRetriever 独立度量 rag_hit，回答质量单独判定引用/拒答）；</li>
 *   <li>评测身份由服务端设定（学员视角 / 管理员测试视角），绝不采信外部传入身份；</li>
 *   <li>token 只取 provider 真实 usage；无 usage → null + unavailable（MOCK → mock_na）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvalAnswerRunner {

    private final ChatProvider chatProvider;
    private final PromptManager promptManager;
    private final AiProperties props;
    private final EvalRefusalRules refusalRules;

    /** 回答执行结果 */
    public record AnswerOutcome(
            String answer,
            Integer answerLatencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String tokenSource,
            boolean refusal,
            String refusalRule,
            boolean hasCitation,
            boolean leakSuspected,
            String errorCode,
            boolean timeout) {

        public boolean execFailed() {
            return errorCode != null;
        }
    }

    /**
     * 生成回答并做自动判定。
     *
     * @param agent         路由命中的智能体（回答模板来源）
     * @param question      评测问题
     * @param ragContext    RAG 检索上下文（docId → {title, snippet}）；无检索为空
     * @param recallTitles  召回文档标题（docId → title）
     * @param expectRefusal 标注「应当拒答」
     * @param directOther   路由结果为 OTHER 直答
     */
    public AnswerOutcome run(AgentTypeEnum agent, String question,
                             Map<String, Map<String, String>> ragContext,
                             Map<String, String> recallTitles,
                             boolean expectRefusal, boolean directOther) {
        AgentPromptSpec spec = promptManager.get(agent == null ? AgentTypeEnum.ROUTE : agent);
        StringBuilder system = new StringBuilder(spec == null ? "" : spec.getSystemPrompt());
        if (!ragContext.isEmpty()) {
            system.append("\n\n【知识库检索结果（评测注入，来源见文档标题）】");
            int i = 1;
            for (Map.Entry<String, Map<String, String>> e : ragContext.entrySet()) {
                Map<String, String> v = e.getValue();
                system.append("\n[").append(i++).append("] 文档《")
                        .append(v.getOrDefault("title", "未知"))
                        .append("》：").append(v.getOrDefault("snippet", ""));
            }
            system.append("\n若以上检索结果不足以回答，请明确说明无法依据现有资料回答，禁止编造。");
            system.append("\n回答请引用所依据的文档标题（如「依据《…》」）。");
        }
        List<Message> messages = List.of(
                new SystemMessage(system.toString()),
                new UserMessage(question));

        long t0 = System.currentTimeMillis();
        ChatProvider.CallResult r;
        boolean timeout = false;
        try {
            // M15.7 备注：max_tokens 在 M6.1 不可设置（options 死字段，字节码实证）——
            // 费用约束由调用次数预算 + TopK 限制 + 批次 30min deadline 承担（EvalRunRunner）
            r = chatProvider.callWithUsage(messages);
        } catch (com.kzai.aigc.provider.ProviderException pe) {
            boolean isTimeout = pe.getKind() == com.kzai.aigc.provider.ProviderException.Kind.TIMEOUT;
            return new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    "PROVIDER_" + pe.getKind() + ":" + pe.getMessage(), isTimeout);
        } catch (RuntimeException ex) {
            return new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    ex.getClass().getSimpleName() + ":" + ex.getMessage(), timeout);
        }
        int cost = (int) (System.currentTimeMillis() - t0);
        String answer = r.text() == null ? "" : r.text();
        String tokenSource = chatProvider.mock()
                ? AiEvalResult.TOKEN_MOCK_NA
                : (r.totalTokens() == null ? AiEvalResult.TOKEN_UNAVAILABLE : AiEvalResult.TOKEN_PROVIDER);

        EvalRefusalRules.RefusalVerdict verdict =
                refusalRules.judgeV2(answer, answer.isBlank(), directOther, expectRefusal);
        boolean citation = refusalRules.hasCitation(answer, recallTitles);
        boolean leak = refusalRules.leakSuspected(answer, expectRefusal, recallTitles);

        return new AnswerOutcome(answer, cost, r.promptTokens(), r.completionTokens(),
                r.totalTokens(), tokenSource, verdict.refusal(), verdict.rule(),
                citation, leak, null, false);
    }

    /** Prompt 生效快照摘要（五类模板 SHA-256 前缀 + Nacos 版本） */
    public String promptSnapshot() {
        try {
            Map<String, Object> status = promptManager.nacosStatus();
            StringBuilder sb = new StringBuilder();
            Object version = status.get("appliedVersion");
            sb.append("promptVersion=").append(version == null ? "builtin" : version);
            for (AgentTypeEnum t : AgentTypeEnum.values()) {
                AgentPromptSpec spec = promptManager.get(t);
                String p = spec == null || spec.getSystemPrompt() == null ? "" : spec.getSystemPrompt();
                sb.append('|').append(t.name()).append('=') 
                        .append(Integer.toHexString(p.hashCode()));
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return "snapshot_error:" + ex.getMessage();
        }
    }

    /** TopK 配置快照（批次记录） */
    public int topK() {
        return props.getKnowledgeTopK();
    }

    // ------------------------------------------------------------------
    // M15：BUY 类 REAL 评测 Dry-run（受控预下单回调——零副作用验证模型工具选择）
    // ------------------------------------------------------------------

    /** BUY Dry-run 结果：回答 Outcome + 受控记录摘要 + 尝试次数 */
    public record BuyDryRunOutcome(AnswerOutcome outcome, String dryRunSummary, int attempts) {
    }

    // ------------------------------------------------------------------
    // M15.9：只读工具受控执行（RECOMMEND/CONSULT/KNOWLEDGE 用例真实工具选择验证）
    // ------------------------------------------------------------------

    /** 只读工具执行记录（模型选择/参数/结果） */
    public record ReadOnlyToolRecord(String toolName, String argsJson, String resultSnippet, boolean success) {
    }

    /**
     * 录制型只读回调：委托真实只读工具执行（内部 Feign 查询，免费）并记录
     * 模型选择与参数——证据来源为真实执行而非模型自述。
     * 安全红线：只允许包装只读回调（调用方显式传入），绝不包装 createPreOrder。
     */
    public static FunctionCallback recording(FunctionCallback delegate,
                                             List<ReadOnlyToolRecord> records) {
        String name = delegate.getName();
        // 直接实现接口：getInputTypeSchema 透传原回调 schema（模型参数生成与真实工具完全一致）
        return new FunctionCallback() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return delegate.getDescription();
            }

            @Override
            public String getInputTypeSchema() {
                return delegate.getInputTypeSchema();
            }

            @Override
            public String call(String toolInput) {
                return delegate.call(toolInput);
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                long t0 = System.currentTimeMillis();
                String result;
                boolean ok;
                try {
                    result = delegate.call(toolInput, toolContext);
                    ok = true;
                } catch (RuntimeException ex) {
                    result = "{\"status\":\"EXEC_ERROR\",\"message\":\""
                            + ex.getMessage() + "\"}";
                    ok = false;
                }
                records.add(new ReadOnlyToolRecord(name,
                        toolInput == null ? "" : toolInput,
                        result == null ? "" : result.substring(0, Math.min(result.length(), 200)),
                        ok));
                return result;
            }
        };
    }

    /** 只读工具受控执行结果 */
    public record ReadToolOutcome(AnswerOutcome outcome, String toolExecSummary, int toolCalls) {
    }

    /**
     * REAL 只读工具用例专用：挂受控只读回调（真实执行，免费）验证模型工具选择/参数/结果一致性。
     * 白名单红线：回调列表由调用方（EvalRunRunner）显式构建，绝不含 createPreOrder。
     */
    public ReadToolOutcome runWithReadTools(AgentTypeEnum agent, String question,
                                            Map<String, Map<String, String>> ragContext,
                                            Map<String, String> recallTitles,
                                            List<FunctionCallback> readOnlyCallbacks,
                                            Map<String, Object> toolContext,
                                            boolean expectRefusal) {
        List<ReadOnlyToolRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<FunctionCallback> wrapped = readOnlyCallbacks.stream()
                .map(cb -> recording(cb, records)).toList();
        AgentPromptSpec spec = promptManager.get(agent == null ? AgentTypeEnum.ROUTE : agent);
        StringBuilder system = new StringBuilder(spec == null ? "" : spec.getSystemPrompt());
        if (!ragContext.isEmpty()) {
            system.append("\n\n【知识库检索结果（评测注入，来源见文档标题）】");
            int i = 1;
            for (Map.Entry<String, Map<String, String>> e : ragContext.entrySet()) {
                Map<String, String> v = e.getValue();
                system.append("\n[").append(i++).append("] 文档《")
                        .append(v.getOrDefault("title", "未知")).append("》：")
                        .append(v.getOrDefault("snippet", ""));
            }
        }
        List<Message> messages = List.of(
                new SystemMessage(system.toString()),
                new UserMessage(question));
        long t0 = System.currentTimeMillis();
        ChatProvider.CallResult r;
        try {
            r = chatProvider.callWithReadTools(messages, wrapped, toolContext);
        } catch (com.kzai.aigc.provider.ProviderException pe) {
            boolean isTimeout = pe.getKind() == com.kzai.aigc.provider.ProviderException.Kind.TIMEOUT;
            return new ReadToolOutcome(new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    "PROVIDER_" + pe.getKind() + ":" + pe.getMessage(), isTimeout),
                    summarizeRecords(records), records.size());
        } catch (RuntimeException ex) {
            return new ReadToolOutcome(new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    ex.getClass().getSimpleName() + ":" + ex.getMessage(), false),
                    summarizeRecords(records), records.size());
        }
        int cost = (int) (System.currentTimeMillis() - t0);
        String answer = r.text() == null ? "" : r.text();
        String tokenSource = chatProvider.mock()
                ? AiEvalResult.TOKEN_MOCK_NA
                : (r.totalTokens() == null ? AiEvalResult.TOKEN_UNAVAILABLE : AiEvalResult.TOKEN_PROVIDER);
        var verdict = refusalRules.judgeV2(answer, answer.isBlank(), false, expectRefusal);
        boolean hasCitation = refusalRules.hasCitation(answer, recallTitles);
        boolean leak = refusalRules.leakSuspected(answer, expectRefusal, recallTitles);
        return new ReadToolOutcome(new AnswerOutcome(answer, cost, r.promptTokens(), r.completionTokens(),
                r.totalTokens(), tokenSource, verdict.refusal(), verdict.refusal() ? verdict.rule() : null,
                hasCitation, leak, null, false),
                summarizeRecords(records), records.size());
    }

    private static String summarizeRecords(List<ReadOnlyToolRecord> records) {
        if (records.isEmpty()) {
            return "无工具调用";
        }
        StringBuilder sb = new StringBuilder();
        for (ReadOnlyToolRecord rec : records) {
            sb.append("\n- tool=").append(rec.toolName())
                    .append(" ok=").append(rec.success())
                    .append(" args=").append(rec.argsJson())
                    .append(" result=").append(rec.resultSnippet());
        }
        return sb.toString();
    }

    /**
     * REAL BUY 用例专用：挂受控 createPreOrder Dry-run 回调（与真实工具同名同参）+ 可选真实只读回调
     * （M15.10：模型购买前需查询课程——同时挂 getCourseDetail 真实只读回调，
     * 避免模型幻觉查询工具名导致执行失败）。
     * 回调仅记录（工具名/参数/校验），零业务副作用；真实只读回调可真实执行（免费）。
     */
    public BuyDryRunOutcome runBuyDryRun(AgentTypeEnum agent, String question, Long courseId,
                                         List<FunctionCallback> extraReadOnlyCallbacks) {
        List<com.kzai.aigc.eval.DryRunPreOrderCallback.DryRunSelection> records =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentPromptSpec spec = promptManager.get(agent == null ? AgentTypeEnum.BUY : agent);
        StringBuilder system = new StringBuilder(spec == null ? "" : spec.getSystemPrompt());
        system.append("\n\n【评测场景说明】用户希望购买课程").append(courseId == null ? "（未指定）" : courseId)
                .append("，已明确确认。请按你的工具使用策略执行。");
        List<Message> messages = List.of(
                new SystemMessage(system.toString()),
                new UserMessage(question));
        java.util.List<FunctionCallback> callbacks = new java.util.ArrayList<>();
        callbacks.add(com.kzai.aigc.eval.DryRunPreOrderCallback.create(records));
        if (extraReadOnlyCallbacks != null) {
            callbacks.addAll(extraReadOnlyCallbacks);
        }
        long t0 = System.currentTimeMillis();
        ChatProvider.CallResult r;
        try {
            r = chatProvider.callWithDryRunTools(messages, callbacks);
        } catch (com.kzai.aigc.provider.ProviderException pe) {
            boolean isTimeout = pe.getKind() == com.kzai.aigc.provider.ProviderException.Kind.TIMEOUT;
            return new BuyDryRunOutcome(new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    "PROVIDER_" + pe.getKind() + ":" + pe.getMessage(), isTimeout),
                    DryRunPreOrderCallback.summarize(records), records.size());
        } catch (RuntimeException ex) {
            return new BuyDryRunOutcome(new AnswerOutcome(null, null, null, null, null,
                    AiEvalResult.TOKEN_UNAVAILABLE, false, null, false, false,
                    ex.getClass().getSimpleName() + ":" + ex.getMessage(), false),
                    DryRunPreOrderCallback.summarize(records), records.size());
        }
        int cost = (int) (System.currentTimeMillis() - t0);
        String answer = r.text() == null ? "" : r.text();
        String tokenSource = chatProvider.mock()
                ? AiEvalResult.TOKEN_MOCK_NA
                : (r.totalTokens() == null ? AiEvalResult.TOKEN_UNAVAILABLE : AiEvalResult.TOKEN_PROVIDER);
        var verdict = refusalRules.judgeV2(answer, answer.isBlank(), false, false);
        boolean refusal = verdict.refusal();
        boolean hasCitation = refusalRules.hasCitation(answer, java.util.Map.of());
        return new BuyDryRunOutcome(new AnswerOutcome(answer, cost, r.promptTokens(), r.completionTokens(),
                r.totalTokens(), tokenSource, refusal, refusal ? "REFUSAL_RULE" : null,
                hasCitation, false, null, false),
                DryRunPreOrderCallback.summarize(records), records.size());
    }
}
