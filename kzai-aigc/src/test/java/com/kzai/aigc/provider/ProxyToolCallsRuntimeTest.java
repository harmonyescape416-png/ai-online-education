package com.kzai.aigc.provider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.DefaultFunctionCallbackResolver;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 1.0.0-M6 proxyToolCalls 运行级语义验证（M8 首项 M7 核验）。
 * <p>
 * 字节码核验结论（DashScopeChatModel 1.0.0-M6.1 call/stream + core AbstractToolCallSupport）：
 * <pre>
 * // call/stream 均为：
 * if (!isProxyToolCalls(prompt, defaultOptions) && isToolCall(resp, TOOL_CALLS|STOP)) {
 *     List&lt;Message&gt; results = handleToolCalls(prompt, resp);    // 框架执行工具
 *     return call/stream(new Prompt(results, prompt.getOptions())); // 结果回传模型续轮
 * }
 * return resp;  // proxyToolCalls=TRUE → 不执行，直接返回 ToolCall 元数据
 *
 * // schema 发送与执行解析都来自构造期 register = merge(defaultOptions, toolFunctionCallbacks)
 * // 运行期 options 的回调只提供名字；名字不在 register → IllegalStateException
 * </pre>
 * 本测试用「可检测工具执行次数」的脚本化模型走<b>真实框架代码</b>复现该语义，
 * 并固化 M8 对 M7 两处缺陷的修复（proxyToolCalls=TRUE 误设；register 未注册工具回调）。
 */
class ProxyToolCallsRuntimeTest {

    /** 与 DashScopeChatModel 相同控制流的计数模型（脚本化响应，不触网） */
    static class CountingToolModel extends org.springframework.ai.chat.model.AbstractToolCallSupport
            implements org.springframework.ai.chat.model.ChatModel {
        final Deque<ChatResponse> script = new ArrayDeque<>();
        final AtomicInteger modelInvocations = new AtomicInteger();
        final java.util.List<Prompt> promptsSeen = new java.util.ArrayList<>();

        CountingToolModel(FunctionCallback registerTool) {
            super(new DefaultFunctionCallbackResolver(), FunctionCallingOptions.builder().build(),
                    registerTool == null ? List.of() : List.of(registerTool));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            modelInvocations.incrementAndGet();
            promptsSeen.add(prompt);
            ChatResponse resp = script.poll();
            if (resp == null) {
                throw new IllegalStateException("脚本耗尽");
            }
            // 与 DashScopeChatModel.call（字节码核验）一致的控制流
            if (!isProxyToolCalls(prompt, FunctionCallingOptions.builder().build())
                    && isToolCall(resp, Set.of("TOOL_CALLS", "STOP"))) {
                List<Message> results = handleToolCalls(prompt, resp);
                return call(new Prompt(results, prompt.getOptions()));
            }
            return resp;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            // 流式下框架同样先执行工具再续轮（stream 字节码同门控）
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    public static class EchoRequest {
        private String q;

        public String getQ() {
            return q;
        }

        public void setQ(String q) {
            this.q = q;
        }
    }

    private static FunctionCallback countingTool(AtomicInteger executions, java.util.List<ToolContext> contexts) {
        return FunctionCallback.builder()
                .function("searchCourses", (java.util.function.BiFunction<EchoRequest, ToolContext, String>) (req, ctx) -> {
                    executions.incrementAndGet();
                    if (contexts != null) {
                        contexts.add(ctx);
                    }
                    return "{\"status\":\"OK\",\"courses\":[{\"courseId\":\"101\",\"name\":\"Java 基础\"}]}";
                })
                .description("测试工具")
                .inputType(EchoRequest.class)
                .build();
    }

    private static ChatResponse toolCallResponse() {
        AssistantMessage msg = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "searchCourses", "{\"q\":\"Java\"}")));
        Generation gen = new Generation(msg, ChatGenerationMetadata.builder()
                .finishReason("TOOL_CALLS").build());
        return new ChatResponse(List.of(gen));
    }

    private static ChatResponse textResponse(String text) {
        Generation gen = new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder()
                .finishReason("STOP").build());
        return new ChatResponse(List.of(gen));
    }

    @Test
    void proxyUnset_frameworkExecutesTool_resultPassedBack_finalTextReturned() {
        AtomicInteger executions = new AtomicInteger();
        FunctionCallback tool = countingTool(executions, null);
        CountingToolModel model = new CountingToolModel(tool); // register 注册（修复后装配）
        model.script.add(toolCallResponse());
        model.script.add(textResponse("根据查询结果，为您找到 Java 基础课程"));

        ChatResponse out = model.call(new Prompt(
                List.of(new UserMessage("推荐Java课程")),
                FunctionCallingOptions.builder()
                        .functionCallbacks(List.of(tool)) // 运行期白名单（按名解析）
                        .toolContext(Map.of("kzaiUserId", 3L))
                        .build()));

        assertEquals(1, executions.get(), "proxyToolCalls 缺省（false）时框架必须真实执行工具");
        assertEquals(2, model.modelInvocations.get(), "工具结果必须回传模型续轮（第二次模型调用）");
        Prompt second = model.promptsSeen.get(1);
        ToolResponseMessage trm = second.getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .findFirst().orElse(null);
        assertNotNull(trm, "工具结果必须以 ToolResponseMessage 传回模型");
        assertEquals("call-1", trm.getResponses().get(0).id(), "工具结果与 ToolCall id 对应");
        assertTrue(trm.getResponses().get(0).responseData().contains("Java 基础"));
        assertEquals("根据查询结果，为您找到 Java 基础课程", out.getResult().getOutput().getText(),
                "模型基于工具结果继续生成最终文本");
        assertFalse(out.getResult().getOutput().hasToolCalls());
    }

    @Test
    void proxyTrue_toolNeverExecuted_rawToolCallReturned_documentsM7Defect1() {
        AtomicInteger executions = new AtomicInteger();
        FunctionCallback tool = countingTool(executions, null);
        CountingToolModel model = new CountingToolModel(tool);
        model.script.add(toolCallResponse());
        model.script.add(textResponse("不应到达"));

        ChatResponse out = model.call(new Prompt(
                List.of(new UserMessage("推荐Java课程")),
                FunctionCallingOptions.builder()
                        .functionCallbacks(List.of(tool))
                        .toolContext(Map.of("kzaiUserId", 3L))
                        .proxyToolCalls(Boolean.TRUE) // ← M7 曾误设 TRUE
                        .build()));

        assertEquals(0, executions.get(), "缺陷实证：proxyToolCalls=TRUE 时工具从不执行");
        assertEquals(1, model.modelInvocations.get(), "不发生续轮");
        assertTrue(out.getResult().getOutput().hasToolCalls(),
                "只返回 ToolCall 元数据（M7 误配置会导致真实模型工具失效）");
    }

    @Test
    void emptyRegister_executionFails_documentsM7Defect2() {
        AtomicInteger executions = new AtomicInteger();
        FunctionCallback tool = countingTool(executions, null);
        CountingToolModel model = new CountingToolModel(null); // M7 原装配：register 为空
        model.script.add(toolCallResponse());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                model.call(new Prompt(
                        List.of(new UserMessage("q")),
                        FunctionCallingOptions.builder()
                                .functionCallbacks(List.of(tool))
                                .build())));
        assertTrue(ex.getMessage().contains("No function callback found"),
                "缺陷实证：register 未注册工具回调时执行解析失败（M7 原装配无法执行真实工具）");
        assertEquals(0, executions.get());
    }

    @Test
    void toolContext_reachesCallback_duringRealFrameworkExecution() {
        AtomicInteger executions = new AtomicInteger();
        java.util.List<ToolContext> contexts = new java.util.ArrayList<>();
        FunctionCallback tool = countingTool(executions, contexts);
        CountingToolModel model = new CountingToolModel(tool);
        model.script.add(toolCallResponse());
        model.script.add(textResponse("ok"));

        model.call(new Prompt(
                List.of(new UserMessage("q")),
                FunctionCallingOptions.builder()
                        .functionCallbacks(List.of(tool))
                        .toolContext(Map.of("kzaiUserId", 7L, "kzaiSessionId", "s1"))
                        .build()));

        assertEquals(1, contexts.size());
        assertEquals(7L, ((Number) contexts.get(0).getContext().get("kzaiUserId")).longValue(),
                "真实框架执行路径下 ToolContext 必须到达回调（可信身份传递前提）");
        assertEquals("s1", contexts.get(0).getContext().get("kzaiSessionId"));
    }

    @Test
    void streamPath_sameSemantics() {
        AtomicInteger executions = new AtomicInteger();
        FunctionCallback tool = countingTool(executions, null);
        CountingToolModel model = new CountingToolModel(tool);
        model.script.add(toolCallResponse());
        model.script.add(textResponse("流式最终回答"));

        ChatResponse out = model.stream(new Prompt(
                List.of(new UserMessage("q")),
                FunctionCallingOptions.builder()
                        .functionCallbacks(List.of(tool))
                        .toolContext(Map.of())
                        .build())).blockLast();

        assertEquals(1, executions.get(), "流式路径同样由框架执行工具");
        assertEquals(2, model.modelInvocations.get(), "流式路径同样续轮");
        assertNotNull(out);
        assertEquals("流式最终回答", out.getResult().getOutput().getText());
    }

    @Test
    void alwaysToolCallModel_noBuiltInIterationGuard_documentedLimitation() {
        // M6 局限记录：handleToolCalls 递归无最大迭代保护——若模型无限返回 ToolCall，
        // 框架将无限续轮。工程兜底 = 工具侧轮次预算（ToolResultHolder.tryAcquireToolCall）。
        com.kzai.aigc.tools.ToolResultHolder round =
                new com.kzai.aigc.tools.ToolResultHolder(1, "s", "r");
        for (int i = 0; i < com.kzai.aigc.tools.ToolResultHolder.MAX_TOOL_CALLS_PER_TOOL; i++) {
            assertTrue(round.tryAcquireToolCall("searchCourses"));
        }
        assertFalse(round.tryAcquireToolCall("searchCourses"),
                "超过预算必须拒绝（防无限工具循环的工程兜底）");
    }
}
