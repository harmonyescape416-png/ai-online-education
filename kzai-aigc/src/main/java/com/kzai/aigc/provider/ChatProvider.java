package com.kzai.aigc.provider;

import com.kzai.aigc.tools.ToolResultHolder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.function.FunctionCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 大模型提供方抽象（M6 → M7）。
 * <p>
 * 不同时期可能启用 DashScope 真实模型或 MOCK Provider；二者契约完全一致以便 ChatService 透传。
 * MOCK 模式用于无 API Key 场景下验证 SSE 协议与流式管线，所有响应必须在 UI/接口/报告中标识 MOCK，
 * 不得冒充真实模型。
 * <p>
 * M7 新增工具调用契约：真实模型 Provider 以 Spring AI 1.0.0-M6 的
 * <code>ChatClient.functions(FunctionCallback...)</code> + <code>toolContext(Map)</code>
 * 实现；工具由模型（AbstractToolCallSupport）在流式过程中自动执行。
 * MOCK Provider 默认不支持工具（由 MockToolScriptService 以同一套工具实现做协议验证）。
 */
public interface ChatProvider {

    /** 提供方编码：用于响应/日志标识，真实模型固定为 DASHSCOPE，MOCK 为 MOCK */
    String code();

    /** 是否 MOCK（用于 UI 显著标识与测试分类） */
    boolean mock();

    /** 非流式调用：返回完整文本，调用失败抛出 ProviderException */
    String call(List<Message> messages);

    /**
     * 非流式调用 + 真实 token 用量采集（M12 评测专用）。
     * 默认实现无 usage（null）；真实 Provider 必须覆盖并返回模型返回的 usage，
     * <b>禁止</b>以输出长度估算冒充（估算必须显式标记 estimated，且不得混入实际用量）。
     * <p>
     * M15.7 备注：max_tokens 应用层约束在 1.0.0-M6.1 不可用——
     * DashScopeChatOptions.maxTokens 为死字段（builder 无 withMaxTokens、setMaxTokens()
     * 为损坏的无参只读方法、Jackson 反序列化不映射，三项字节码/运行时实证）。
     * 评测费用约束由调用次数预算 + TopK 限制 + 批次 30min deadline 共同承担（见 EvalRunRunner）。
     */
    default CallResult callWithUsage(List<Message> messages) {
        return new CallResult(call(messages), null, null, null);
    }

    /**
     * M15：BUY 类评测 Dry-run——带「受控运行期工具回调」的非流式调用。
     * 运行期回调与真实工具同名（模型 schema 一致），但执行体为受控记录（零副作用）；
     * proxyToolCalls=FALSE → 框架真实执行运行期回调并续轮（M7/M8 已验证语义）。
     * 默认仅真实 Provider 支持（MOCK 由 MockToolScript 独立验证协议）。
     */
    default CallResult callWithDryRunTools(List<Message> messages,
                                           List<org.springframework.ai.model.function.FunctionCallback> dryRunCallbacks) {
        throw new UnsupportedOperationException("callWithDryRunTools 不支持: " + code());
    }

    /**
     * M15.9：只读工具受控执行调用（评测专用）。
     * 与 Dry-run 同隔离模式：独立 ChatModel 构造期<b>仅注册传入的只读回调</b>
     * （searchCourses/getCourseDetail/searchKnowledge——不得传入任何写工具），
     * 工具由框架真实执行（内部 Feign 只读查询，免费）并续轮；ToolContext 经
     * 运行期 DashScopeChatOptions.withToolContext 传递（M6.1 AbstractToolCallSupport
     * 从 Prompt options 读取 ToolContext——字节码实证）。
     * 安全红线：本方法禁止用于传递 createPreOrder 等写回调。
     */
    default CallResult callWithReadTools(List<Message> messages,
                                         List<org.springframework.ai.model.function.FunctionCallback> readOnlyCallbacks,
                                         Map<String, Object> toolContext) {
        throw new UnsupportedOperationException("callWithReadTools 不支持: " + code());
    }

    /** 非流式调用结果：文本 + 可选真实 token 用量（null=provider 未返回） */
    record CallResult(String text, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /** 流式调用：逐段返回文本增量，调用失败通过 Flux.onError 表达 */
    Flux<String> stream(List<Message> messages);

    /** 是否支持 Spring AI 工具调用（真实模型 true；MOCK 默认 false） */
    default boolean supportsTools() {
        return false;
    }

    /**
     * 带工具的流式调用（M7）。
     *
     * @param messages    含系统提示词与历史的消息列表
     * @param tools       工具回调（FunctionCallback，M6 API）
     * @param toolContext 可信上下文（userId/roles/requestId/sessionId/round），非模型可变
     * @param round       本轮工具结果暂存（卡片去重、token 用量采集）
     */
    default Flux<String> streamWithTools(List<Message> messages, List<FunctionCallback> tools,
                                         Map<String, Object> toolContext, ToolResultHolder round) {
        throw new UnsupportedOperationException("当前 Provider 不支持工具调用: " + code());
    }
}
