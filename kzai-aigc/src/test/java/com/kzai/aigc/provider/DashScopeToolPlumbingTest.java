package com.kzai.aigc.provider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 1.0.0-M6 工具调用管线兼容性验证（M7 关键）。
 * <p>
 * 用桩 ChatModel 捕获真实 ChatClient 传入的 Prompt，验证：
 * 1) <code>functions(FunctionCallback...)</code> 确实把回调注入运行时 FunctionCallingOptions；
 * 2) <code>toolContext(Map)</code> 确实随 Prompt 传递并在工具执行时到达回调；
 * 3) 流式路径 stream().chatResponse() 可读 usage 元数据。
 * 全部签名来自本地 spring-ai-core 1.0.0-M6 jar（javap 核验），非新版示例照搬。
 */
class DashScopeToolPlumbingTest {

    static class CapturingChatModel implements ChatModel {
        Prompt captured;
        final boolean streamMode;

        CapturingChatModel(boolean streamMode) {
            this.streamMode = streamMode;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.captured = prompt;
            return response("ok", null);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.captured = prompt;
            return Flux.just(
                    response("你", usage(10, 2)),
                    response("好", usage(10, 4)));
        }

        private static ChatResponse response(String text, Usage usage) {
            Generation gen = new Generation(new AssistantMessage(text), ChatGenerationMetadata.NULL);
            if (usage == null) {
                return new ChatResponse(List.of(gen));
            }
            ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
            return new ChatResponse(List.of(gen), metadata);
        }

        private static Usage usage(int promptTokens, int completionTokens) {
            return new Usage() {
                @Override
                public Integer getPromptTokens() {
                    return promptTokens;
                }

                @Override
                public Integer getCompletionTokens() {
                    return completionTokens;
                }

                @Override
                public Object getNativeUsage() {
                    return null;
                }
            };
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

    @Test
    void m6ChatClient_propagatesCallbacksAndToolContext_onlyWithFunctionCallingOptions() {
        CapturingChatModel model = new CapturingChatModel(true);
        ChatClient client = ChatClient.builder(model).build();

        FunctionCallback echo = FunctionCallback.builder()
                .function("searchCourses", (BiFunction<EchoRequest, ToolContext, String>) (req, tc) -> "{\"status\":\"OK\"}")
                .description("测试工具")
                .inputType(EchoRequest.class)
                .build();

        Map<String, Object> toolContext = Map.of("kzaiUserId", 3L, "kzaiSessionId", "s1");

        // 正确姿势（M6）：Prompt 自带 FunctionCallingOptions → 回调与上下文可达模型
        FunctionCallingOptions options = FunctionCallingOptions.builder()
                .functionCallbacks(List.of(echo))
                .toolContext(toolContext)
                .proxyToolCalls(Boolean.TRUE)
                .build();
        client.prompt(new Prompt(List.of(new UserMessage("查一下课程")), options))
                .stream().content().blockLast();

        assertNotNull(model.captured, "桩模型必须收到 Prompt");
        ChatOptions seen = model.captured.getOptions();
        assertInstanceOf(FunctionCallingOptions.class, seen,
                "M6 下 Prompt 携带 FunctionCallingOptions 时回调可直达模型");
        FunctionCallingOptions fopts = (FunctionCallingOptions) seen;
        assertNotNull(fopts.getFunctionCallbacks());
        assertEquals("searchCourses", fopts.getFunctionCallbacks().get(0).getName());
        assertEquals(3L, fopts.getToolContext().get("kzaiUserId"));
        assertEquals(Boolean.TRUE, fopts.getProxyToolCalls());
    }

    @Test
    void m6Pitfall_plainPrompt_dropsRuntimeCallbacks() {
        // 已核验的 M6 陷阱（AdvisedRequest.toPrompt 字节码）：普通 Prompt（DefaultChatOptions）
        // 时，请求规格上的 functions/toolContext 会被静默丢弃——M7 工具调用禁止此写法。
        CapturingChatModel model = new CapturingChatModel(true);
        ChatClient client = ChatClient.builder(model).build();

        FunctionCallback echo = FunctionCallback.builder()
                .function("searchCourses", (BiFunction<EchoRequest, ToolContext, String>) (req, tc) -> "x")
                .description("d")
                .inputType(EchoRequest.class)
                .build();

        client.prompt(new Prompt(List.of(new UserMessage("查一下课程"))))
                .functions(echo)
                .toolContext(Map.of("kzaiUserId", 3L))
                .stream().content().blockLast();

        ChatOptions seen = model.captured.getOptions();
        assertTrue(!(seen instanceof FunctionCallingOptions),
                "M6 普通 Prompt 下回调不会进入模型 options（这正是必须携带 FunctionCallingOptions 的原因）");
    }

    @Test
    void m6ChatResponseMetadata_usageReadable() {
        CapturingChatModel model = new CapturingChatModel(true);
        ChatClient client = ChatClient.builder(model).build();
        AtomicReference<Integer> total = new AtomicReference<>();
        client.prompt(new Prompt(List.of(new UserMessage("hi"))))
                .stream().chatResponse()
                .doOnNext(resp -> {
                    if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                        Usage u = resp.getMetadata().getUsage();
                        if (u.getPromptTokens() != null) {
                            total.set(u.getPromptTokens());
                        }
                    }
                })
                .blockLast();
        assertEquals(10, total.get(), "M6 流式响应可读取 usage（token 用量采集前提）");
    }

    @Test
    void functionCallback_callWithToolContext_defaultBridge() {
        // M6 FunctionCallback 接口默认方法 call(String, ToolContext)：
        // 验证 builder 构建的回调在带 ToolContext 调用时的桥接行为
        AtomicReference<ToolContext> got = new AtomicReference<>();
        FunctionCallback cb = FunctionCallback.builder()
                .function("getCourseDetail", (BiFunction<EchoRequest, ToolContext, String>) (req, tc) -> {
                    got.set(tc);
                    return "detail";
                })
                .description("d")
                .inputType(EchoRequest.class)
                .build();
        ToolContext tc = new ToolContext(Map.of("kzaiRequestId", "r-1"));
        String out = cb.call("{\"q\":\"x\"}", tc);
        assertEquals("detail", out);
        assertNotNull(got.get());
        assertEquals("r-1", got.get().getContext().get("kzaiRequestId"));
        assertEquals("getCourseDetail", cb.getName());
    }

    @Test
    void assistantMessage_m6TextApi() {
        AssistantMessage m = new AssistantMessage("正文");
        assertEquals("正文", m.getText(), "M6 取文本 API 是 getText()");
        assertTrue(!m.hasToolCalls());
    }
}
