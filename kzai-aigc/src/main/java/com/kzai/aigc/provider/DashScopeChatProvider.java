package com.kzai.aigc.provider;

import com.kzai.aigc.config.AiModelConfig.DryRunChatClientFactory;
import com.kzai.aigc.tools.ToolResultHolder;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * DashScope（qwen-plus）真实大模型 Provider，基于 Spring AI 1.0.0-M6 ChatClient。
 * <p>
 * 流式调用：把 ChatClient 的 Flux 切到 boundedElastic 避免阻塞事件循环；
 * 非流式调用：直接收集整段文本。
 * 异常处理：
 *  - 上游网络错误/超时 → ProviderException(TIMEOUT)
 *  - 401/403 → ProviderException(UNAUTHORIZED)
 *  - 429/限流 → ProviderException(RATE_LIMIT)
 *  - 其它 4xx/5xx → ProviderException(UPSTREAM)
 * <p>
 * M7 工具调用（M15 REAL 根因修复后的最终形态，1.0.0-M6.1 实证）：
 * <pre>
 * chatClient.prompt()
 *     .messages(messages)
 *     .options(DashScopeChatOptions.withFunctions(白名单名)  // 工具 schema 由运行期白名单名决定
 *              .withToolContext(map).withProxyToolCalls(FALSE))
 *     .stream().chatResponse()          // Flux&lt;ChatResponse&gt;，中间块可携带工具调用
 * </pre>
 * 工具回调执行由模型构造期注册的 register 解析（AiModelConfig 5 参构造），
 * 由 DashScopeChatModel（AbstractToolCallSupport）在流式过程中自动执行并续轮。
 */
@Slf4j
@Component
@ConditionalOnBean(ChatClient.class)
public class DashScopeChatProvider implements ChatProvider {

    private final ChatClient chatClient;
    private final DryRunChatClientFactory dryRunChatClientFactory;

    public DashScopeChatProvider(ChatClient chatClient, DryRunChatClientFactory dryRunChatClientFactory) {
        this.chatClient = chatClient;
        this.dryRunChatClientFactory = dryRunChatClientFactory;
    }

    @Override
    public String code() {
        return "DASHSCOPE";
    }

    @Override
    public boolean mock() {
        return false;
    }

    @Override
    public String call(List<Message> messages) {
        try {
            return chatClient.prompt(new Prompt(messages)).call().content();
        } catch (RuntimeException ex) {
            throw mapError(ex);
        }
    }

    /** M12：非流式调用并采集真实 usage（provider 返回值，不做任何估算） */
    @Override
    public CallResult callWithUsage(List<Message> messages) {
        try {
            ChatResponse resp = chatClient.prompt(new Prompt(messages)).call().chatResponse();
            if (resp == null) {
                throw new ProviderException(ProviderException.Kind.UPSTREAM, code(), "上游返回空响应");
            }
            String text = resp.getResult() == null || resp.getResult().getOutput() == null
                    ? "" : String.valueOf(resp.getResult().getOutput().getText());
            Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
            Integer pt = usage == null || usage.getPromptTokens() == null ? null : usage.getPromptTokens();
            Integer ct = usage == null || usage.getCompletionTokens() == null ? null : usage.getCompletionTokens();
            Integer tt = usage == null || usage.getTotalTokens() == null ? null : usage.getTotalTokens();
            return new CallResult(text, pt, ct, tt);
        } catch (RuntimeException ex) {
            throw mapError(ex);
        }
    }

    /**
     * M15：BUY 评测 Dry-run——用受控回调独立构造 ChatClient（执行体仅记录，零副作用）。
     * M15 REAL 修复：1.0.0-M6.1 下运行期回调注入不可行——
     * AbstractToolCallSupport.resolveFunctionCallbacks 为 register 优先（真实 createPreOrder
     * 会真实调用交易服务），且运行期 options 回调经 ModelOptionsUtils.copyToTarget 无法存活。
     * 独立 ChatModel 的 register 中只有 Dry-run 回调，模型照常续轮，天然零副作用。
     */
    @Override
    public CallResult callWithDryRunTools(List<Message> messages,
                                          List<FunctionCallback> dryRunCallbacks) {
        try {
            ChatClient dryRunClient = dryRunChatClientFactory.create(dryRunCallbacks);
            var spec = dryRunClient.prompt().messages(messages);
            // M15.10 修复：与 callWithReadTools 同理——withFunctions(名字) 使 createPreOrder
            // 替身 schema 进入请求（此前模型看不到 schema，31 条评测 wta=0 实为 schema 缺失所致）
            if (!dryRunCallbacks.isEmpty()) {
                java.util.Set<String> names = new java.util.HashSet<>();
                for (FunctionCallback cb : dryRunCallbacks) {
                    names.add(cb.getName());
                }
                spec.options(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withFunctions(names).build());
            }
            ChatResponse resp = spec.call().chatResponse();
            if (resp == null) {
                throw new ProviderException(ProviderException.Kind.UPSTREAM, code(), "上游返回空响应");
            }
            String text = resp.getResult() == null || resp.getResult().getOutput() == null
                    ? "" : String.valueOf(resp.getResult().getOutput().getText());
            Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
            Integer pt = usage == null || usage.getPromptTokens() == null ? null : usage.getPromptTokens();
            Integer ct = usage == null || usage.getCompletionTokens() == null ? null : usage.getCompletionTokens();
            Integer tt = usage == null || usage.getTotalTokens() == null ? null : usage.getTotalTokens();
            return new CallResult(text, pt, ct, tt);
        } catch (RuntimeException ex) {
            throw mapError(ex);
        }
    }

    /**
     * M15.9：只读工具受控执行（评测专用）。与 Dry-run 同隔离模式：
     * 独立 ChatModel 构造期仅注册只读回调；ToolContext 经运行期
     * DashScopeChatOptions.withToolContext 传递（AbstractToolCallSupport 从
     * Prompt options 读取——字节码实证）。红线：不得传入写回调。
     */
    @Override
    public CallResult callWithReadTools(List<Message> messages,
                                        List<FunctionCallback> readOnlyCallbacks,
                                        Map<String, Object> toolContext) {
        try {
            ChatClient readToolClient = dryRunChatClientFactory.create(readOnlyCallbacks);
            var spec = readToolClient.prompt().messages(messages);
            // M15.10 修复：运行期 withFunctions(名字集合) 使工具 schema 进入请求——
            // getFunctionTools(resolveFunctionCallbacks) 按名字解析且 register 优先命中受控回调；
            // 名字为字符串可经 copyToTarget 存活（M15 实证不可存活的是回调对象）
            if (!readOnlyCallbacks.isEmpty()) {
                java.util.Set<String> names = new java.util.HashSet<>();
                for (FunctionCallback cb : readOnlyCallbacks) {
                    names.add(cb.getName());
                }
                var optsBuilder = com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withFunctions(names);
                if (toolContext != null && !toolContext.isEmpty()) {
                    optsBuilder.withToolContext(toolContext);
                }
                spec.options(optsBuilder.build());
            } else if (toolContext != null && !toolContext.isEmpty()) {
                spec.options(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withToolContext(toolContext).build());
            }
            ChatResponse resp = spec.call().chatResponse();
            if (resp == null) {
                throw new ProviderException(ProviderException.Kind.UPSTREAM, code(), "上游返回空响应");
            }
            String text = resp.getResult() == null || resp.getResult().getOutput() == null
                    ? "" : String.valueOf(resp.getResult().getOutput().getText());
            Usage usage = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
            Integer pt = usage == null || usage.getPromptTokens() == null ? null : usage.getPromptTokens();
            Integer ct = usage == null || usage.getCompletionTokens() == null ? null : usage.getCompletionTokens();
            Integer tt = usage == null || usage.getTotalTokens() == null ? null : usage.getTotalTokens();
            return new CallResult(text, pt, ct, tt);
        } catch (RuntimeException ex) {
            throw mapError(ex);
        }
    }

    @Override
    public Flux<String> stream(List<Message> messages) {
        // 抑制已经到达上游但被前端中断/停止订阅后的额外输出，避免双发
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return chatClient.prompt(new Prompt(messages)).stream().content()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnCancel(() -> cancelled.set(true))
                .takeWhile(s -> !cancelled.get())
                .onErrorMap(ex -> mapError(ex));
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public Flux<String> streamWithTools(List<Message> messages, List<FunctionCallback> tools,
                                        Map<String, Object> toolContext, ToolResultHolder round) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // M15 REAL 根因修复（DashScopeChatModel.createRequest 字节码 + 反射探测实证）：
        // 1.0.0-M6.1 的 createRequest 用 ModelOptionsUtils.copyToTarget（Jackson JSON 复制）
        // 把运行期 options 转为 DashScopeChatOptions，通用 FunctionCallingOptions 的
        // functionCallbacks（对象）与 functions（名字）都会在此【全部丢失】——
        // 请求体 tools=null，模型于是幻觉式「声称调用工具」并编造数据
        // （aigc 线上表现：RECOMMEND 查课程返回编造课程与价格、cardCount=0、工具审计 0）。
        // 唯一可行形态：运行期直接传 DashScopeChatOptions.withFunctions(白名单名)，
        // 回调执行仍由模型构造期注册的 register（AiModelConfig 5 参构造）解析；
        // proxyToolCalls 必须为 FALSE（TRUE 时模型不执行工具，M8 语义）。
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withFunctions(tools.stream().map(FunctionCallback::getName).collect(Collectors.toSet()))
                .withToolContext(toolContext)
                .withProxyToolCalls(Boolean.FALSE)
                .build();
        return chatClient.prompt()
                .messages(messages)
                .options(options)
                // M6 StreamResponseSpec.chatResponse()：拿到 ChatResponse 才能读 usage 元数据
                .stream().chatResponse()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(resp -> collectUsage(resp, round))
                .map(resp -> textOf(resp))
                .filter(s -> s != null && !s.isEmpty())
                .doOnCancel(() -> cancelled.set(true))
                .takeWhile(s -> !cancelled.get())
                .onErrorMap(ex -> mapError(ex));
    }

    private static String textOf(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        // M6 API：AssistantMessage.getText()
        return resp.getResult().getOutput().getText();
    }

    private void collectUsage(ChatResponse resp, ToolResultHolder round) {
        if (resp == null || resp.getMetadata() == null || round == null) {
            return;
        }
        Usage usage = resp.getMetadata().getUsage();
        if (usage != null && (usage.getPromptTokens() != null || usage.getCompletionTokens() != null)) {
            round.collectUsage(usage);
        }
    }

    private ProviderException mapError(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = String.valueOf(ex.getMessage());
        if (msg.contains("401") || msg.contains("invalid api key") || msg.toLowerCase().contains("unauthorized")) {
            return new ProviderException(ProviderException.Kind.UNAUTHORIZED, code(), msg, ex);
        }
        if (msg.contains("429") || msg.toLowerCase().contains("rate limit") || msg.contains("qps")) {
            return new ProviderException(ProviderException.Kind.RATE_LIMIT, code(), msg, ex);
        }
        if (msg.toLowerCase().contains("timeout") || msg.contains("Read timed out")) {
            return new ProviderException(ProviderException.Kind.TIMEOUT, code(), msg, ex);
        }
        return new ProviderException(ProviderException.Kind.UPSTREAM, code(), msg, ex);
    }
}
