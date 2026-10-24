package com.kzai.aigc.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.kzai.aigc.eval.service.EvalAnswerRunner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15.9 只读工具受控执行形态测试：
 * ①录制包装保留原工具名/schema；②执行记录含模型选择与参数（真实执行证据）；
 * ③执行异常记录 EXEC_ERROR（不冒充成功）；④ToolContext 经运行期
 * DashScopeChatOptions.withToolContext 传递（M6.1 AbstractToolCallSupport 从
 * Prompt options 读取——字节码实证）；⑤评测独立模型 register 只含只读回调。
 */
class ReadOnlyToolEvalShapeTest {

    private FunctionCallback fakeReadOnly(String name) {
        return FunctionCallback.builder()
                .function(name, (java.util.function.BiFunction<Map, ToolContext, String>) (req, ctx) ->
                        "{\"status\":\"OK\",\"tool\":\"" + name + "\",\"userId\":" + ctx.getContext().get("kzaiUserId") + "}")
                .description("只读测试工具 " + name)
                .inputType(Map.class)
                .build();
    }

    @Test
    void recording包装保留原名与schema并记录执行() {
        var records = new CopyOnWriteArrayList<EvalAnswerRunner.ReadOnlyToolRecord>();
        FunctionCallback wrapped = EvalAnswerRunner.recording(fakeReadOnly("searchCourses"), records);
        assertEquals("searchCourses", wrapped.getName(), "工具名保持一致（模型 schema 可用）");
        assertEquals(true, wrapped.getInputTypeSchema().contains("\"type\""), "schema 透传原回调");

        String out = wrapped.call("{\"keyword\":\"Java\"}", new ToolContext(Map.of("kzaiUserId", 1L)));
        assertTrue(out.contains("searchCourses") && out.contains("userId\":1"), "真实执行并透传 ToolContext");
        assertEquals(1, records.size(), "执行记录：模型选择+参数+结果");
        assertEquals("searchCourses", records.get(0).toolName());
        assertTrue(records.get(0).success());
        assertTrue(records.get(0).argsJson().contains("Java"));
    }

    @Test
    void recording包装执行异常时记录EXEC_ERROR() {
        FunctionCallback boom = FunctionCallback.builder()
                .function("searchCourses", (java.util.function.BiFunction<Map, ToolContext, String>) (req, ctx) -> {
                    throw new IllegalStateException("course down");
                })
                .description("boom")
                .inputType(Map.class)
                .build();
        var records = new CopyOnWriteArrayList<EvalAnswerRunner.ReadOnlyToolRecord>();
        FunctionCallback wrapped = EvalAnswerRunner.recording(boom, records);
        String out = wrapped.call("{}", new ToolContext(Map.of()));
        assertTrue(out.contains("EXEC_ERROR"), "执行异常被记录为失败（不冒充成功）");
        assertTrue(!records.get(0).success());
    }

    @Test
    void toolContext经运行期DashScopeChatOptions可用() {
        var prompt = new org.springframework.ai.chat.prompt.Prompt(
                List.of(new org.springframework.ai.chat.messages.UserMessage("hi")),
                DashScopeChatOptions.builder().withModel("qwen-plus")
                        .withToolContext(Map.of("kzaiUserId", 1L)).build());
        var opts = prompt.getOptions();
        assertTrue(opts instanceof org.springframework.ai.model.function.FunctionCallingOptions,
                "运行期 options 应为 FunctionCallingOptions（ToolContext 读取来源）");
        assertEquals(1L, ((org.springframework.ai.model.function.FunctionCallingOptions) opts)
                .getToolContext().get("kzaiUserId"));
    }

    @Test
    void dryrun工厂构建的模型register只含只读回调() throws Exception {
        var model = new DashScopeChatModel(new DashScopeApi("sk-dummy"),
                DashScopeChatOptions.builder().withModel("qwen-plus").build(),
                new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(),
                List.of(fakeReadOnly("searchCourses"), fakeReadOnly("getCourseDetail")),
                org.springframework.retry.support.RetryTemplate.builder().build());
        Class<?> c = model.getClass();
        Object register = null;
        while (c != null && register == null) {
            try {
                var f = c.getDeclaredField("functionCallbackRegister");
                f.setAccessible(true);
                register = f.get(model);
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            }
        }
        assertTrue(register instanceof Map<?, ?> m && m.size() == 2
                && m.containsKey("searchCourses") && m.containsKey("getCourseDetail"),
                "register 只含传入的只读回调（真实写工具不可进入评测上下文）");
    }
}
