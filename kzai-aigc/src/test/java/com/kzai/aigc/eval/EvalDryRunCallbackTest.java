package com.kzai.aigc.eval;

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
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15：BUY Dry-run 受控回调的框架级执行验证（用户硬性要求：先以计数模型证明回调可被执行）。
 * 与 DashScopeChatModel 相同控制流的计数模型（ProxyToolCallsRuntimeTest 模式）。
 */
class EvalDryRunCallbackTest {

    static class CountingToolModel extends org.springframework.ai.chat.model.AbstractToolCallSupport
            implements ChatModel {
        final Deque<ChatResponse> script = new ArrayDeque<>();
        final java.util.List<Prompt> promptsSeen = new java.util.ArrayList<>();

        CountingToolModel(FunctionCallback runtimeTool) {
            super(new DefaultFunctionCallbackResolver(), FunctionCallingOptions.builder().build(),
                    List.of(runtimeTool));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            promptsSeen.add(prompt);
            ChatResponse resp = script.poll();
            if (resp == null) {
                throw new IllegalStateException("脚本耗尽");
            }
            if (!isProxyToolCalls(prompt, FunctionCallingOptions.builder().build())
                    && isToolCall(resp, Set.of("TOOL_CALLS", "STOP"))) {
                List<Message> results = handleToolCalls(prompt, resp);
                return call(new Prompt(results, prompt.getOptions()));
            }
            return resp;
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    public static class PreOrderRequest {
        private String courseId;
        public String getCourseId() { return courseId; }
        public void setCourseId(String c) { this.courseId = c; }
    }

    private static FunctionCallback dryRunTool(List<DryRunPreOrderCallback.DryRunSelection> records) {
        return FunctionCallback.builder()
                .function("createPreOrder", (java.util.function.BiFunction<PreOrderRequest, ToolContext, String>) (req, ctx) -> {
                    String courseId = req == null ? null : req.getCourseId();
                    String validation = courseId != null && courseId.matches("\\d{1,19}")
                            ? "OK" : "INVALID_PARAMS:courseId 非法";
                    records.add(new DryRunPreOrderCallback.DryRunSelection(
                            "createPreOrder", courseId, validation, "{\"dryRun\":true}"));
                    return "{\"dryRun\":true,\"message\":\"已记录模型工具选择，未执行下单\"}";
                })
                .description("为用户创建课程待支付订单（预下单）")
                .inputType(PreOrderRequest.class)
                .build();
    }

    private static ChatResponse toolCallResponse(String courseId) {
        AssistantMessage msg = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "createPreOrder", "{\"courseId\":\"" + courseId + "\"}")));
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
    void dryRunCallback_executedByFramework_zeroSideEffect() {
        List<DryRunPreOrderCallback.DryRunSelection> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountingToolModel model = new CountingToolModel(dryRunTool(records));
        model.script.add(toolCallResponse("1004"));
        model.script.add(textResponse("好的，评测 Dry-run 已记录您的购买意向。"));

        ChatResponse out = model.call(new Prompt(List.of(new UserMessage("帮我购买课程 1004")),
                FunctionCallingOptions.builder().proxyToolCalls(Boolean.FALSE).build()));

        assertEquals(1, records.size(), "Dry-run 回调必须被框架真实执行一次");
        DryRunPreOrderCallback.DryRunSelection s = records.get(0);
        assertEquals("createPreOrder", s.toolName());
        assertEquals("1004", s.courseId());
        assertEquals("OK", s.validation());
        assertEquals(2, model.promptsSeen.size());
        boolean toolResponseSeen = model.promptsSeen.get(1).getInstructions().stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
        assertTrue(toolResponseSeen, "工具结果必须回传模型续轮");
        assertEquals("好的，评测 Dry-run 已记录您的购买意向。", out.getResult().getOutput().getText());
        String summary = DryRunPreOrderCallback.summarize(records);
        assertTrue(summary.contains("tool=createPreOrder") && summary.contains("courseId=1004"));
    }

    @Test
    void dryRunCallback_invalidCourseId_recordedAsInvalid() {
        List<DryRunPreOrderCallback.DryRunSelection> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountingToolModel model = new CountingToolModel(dryRunTool(records));
        model.script.add(toolCallResponse("not-a-number"));
        model.script.add(textResponse("参数有误。"));

        model.call(new Prompt(List.of(new UserMessage("买课")),
                FunctionCallingOptions.builder().proxyToolCalls(Boolean.FALSE).build()));

        assertEquals(1, records.size());
        assertEquals("not-a-number", records.get(0).courseId());
        assertTrue(records.get(0).validation().startsWith("INVALID_PARAMS"),
                "非法参数必须被记录为校验失败");
    }
}
