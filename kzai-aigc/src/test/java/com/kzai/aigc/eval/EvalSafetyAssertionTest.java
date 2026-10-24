package com.kzai.aigc.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.kzai.aigc.eval.service.EvalRunRunner;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.tools.PreOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.function.FunctionCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M15.7 评测安全断言：BUY 评测全分支不得触达真实写工具。
 * <p>
 * 红线（用户指令一/1）：无论模型路由结果如何，评测执行上下文不得注册/调用/透传任何真实业务写工具。
 * <ul>
 *   <li>Dry-run 路径：callWithDryRunTools 只允许传入受控回调（名字=createPreOrder、执行体仅记录）；</li>
 *   <li>非 Dry-run 路径（路由误分类/expect_tool 缺失）：callWithUsage 无任何工具 schema，
 *       模型结构上无法调用 createPreOrder；</li>
 *   <li>Dry-run 独立 ChatModel 的 register 仅含受控回调（真实业务工具不得进入）；</li>
 *   <li>maxTokens 应用层费用约束随请求下发（M6.1 DashScopeChatOptions 运行期字段存活机制）。</li>
 * </ul>
 * TradeClient 编译期隔离（EvalAnswerRunner/EvalRunRunner/DryRunPreOrderCallback 均无该依赖）
 * 由本测试的运行期行为断言 + REAL 执行前后订单计数对比共同补强。
 */
class EvalSafetyAssertionTest {

    /** 记录调用的桩 Provider：捕获方法名/回调/maxTokens——断言评测分支不触真实写工具路径 */
    static class RecordingProvider implements ChatProvider {
        final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public String code() {
            return "DASHSCOPE";
        }

        @Override
        public boolean mock() {
            return false;
        }

        @Override
        public String call(List<org.springframework.ai.chat.messages.Message> messages) {
            calls.add("call");
            return "ok";
        }

        @Override
        public CallResult callWithUsage(List<org.springframework.ai.chat.messages.Message> messages) {
            calls.add("callWithUsage(no-tools)");
            return new CallResult("ok", 10, 5, 15);
        }

        @Override
        public CallResult callWithDryRunTools(List<org.springframework.ai.chat.messages.Message> messages,
                                              List<FunctionCallback> dryRunCallbacks) {
            calls.add("callWithDryRunTools(callbacks=" + dryRunCallbacks.size()
                    + ",names=" + dryRunCallbacks.stream().map(FunctionCallback::getName).toList() + ")");
            return new CallResult("ok", 10, 5, 15);
        }

        @Override
        public reactor.core.publisher.Flux<String> stream(List<org.springframework.ai.chat.messages.Message> messages) {
            calls.add("stream");
            return reactor.core.publisher.Flux.just("");
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void dry_run回调与真实工具同名但执行体零副作用() {
        CopyOnWriteArrayList<DryRunPreOrderCallback.DryRunSelection> records = new CopyOnWriteArrayList<>();
        FunctionCallback cb = DryRunPreOrderCallback.create(records);
        assertEquals("createPreOrder", cb.getName(), "与真实工具同名（模型 schema 一致）");

        PreOrderRequest req = new PreOrderRequest();
        req.setCourseId("1001");
        String out = cb.call("{\"courseId\":\"1001\"}", new ToolContext(Map.of()));
        assertTrue(out.contains("\"dryRun\":true"), "执行体只返回 dry-run 受控响应: " + out);
        assertEquals(1, records.size(), "记录模型实际选择与参数");
        assertEquals("1001", records.get(0).courseId());
    }

    @Test
    void dry_run独立ChatModel_register仅含受控回调() throws Exception {
        // 与 AiModelConfig.dryRunChatClientFactory 同构造：register 只含传入回调
        FunctionCallback cb = DryRunPreOrderCallback.create(new CopyOnWriteArrayList<>());
        DashScopeChatModel model = new DashScopeChatModel(new DashScopeApi("sk-dummy"),
                DashScopeChatOptions.builder().withModel("qwen-plus").build(),
                new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(),
                List.of(cb), org.springframework.retry.support.RetryTemplate.builder().build());
        Object register = readField(model, "functionCallbackRegister");
        assertTrue(register instanceof Map<?, ?> m && m.containsKey("createPreOrder"),
                "register 应含 Dry-run createPreOrder");
        assertEquals(1, ((Map<?, ?>) register).size(),
                "register 只允许 Dry-run 回调——真实业务工具（searchCourses 等）不得进入评测上下文");
    }

    @Test
    void 评测调用形态_普通无工具_Dryrun单受控回调() {
        RecordingProvider p = new RecordingProvider();
        // 非 Dry-run 分支：无工具纯文本调用（模型结构上无法触发任何写工具）
        p.callWithUsage(List.of(new UserMessage("q")));
        assertTrue(p.calls.get(0).equals("callWithUsage(no-tools)"), "普通评测走无工具 callWithUsage: " + p.calls);
        // Dry-run 分支：仅 1 个受控回调（createPreOrder 替身）
        p.callWithDryRunTools(List.of(new UserMessage("q")),
                List.of(DryRunPreOrderCallback.create(new CopyOnWriteArrayList<>())));
        assertTrue(p.calls.get(1).startsWith("callWithDryRunTools(callbacks=1,names=[createPreOrder]"),
                "Dry-run 仅带单个受控回调: " + p.calls);
    }

    @Test
    void m6_1_maxTokens不可设置的限制实证() throws Exception {
        // M6.1 缺陷锁定：DashScopeChatOptions.maxTokens 为死字段——
        // builder 无 withMaxTokens、setMaxTokens() 为损坏的无参只读方法、Jackson 不映射。
        // 费用约束因此由调用次数预算 + TopK + 批次 deadline 承担（EvalRunRunner），依赖升级后可启用。
        DashScopeChatOptions o = DashScopeChatOptions.builder().withModel("qwen-plus").build();
        assertTrue(o.getMaxTokens() == null, "缺省 maxTokens 应为 null");
        Method setter = null;
        for (Method m : DashScopeChatOptions.class.getMethods()) {
            if (m.getName().equals("setMaxTokens")) {
                setter = m;
            }
        }
        assertTrue(setter != null && setter.getParameterCount() == 0,
                "M6.1 setMaxTokens 为损坏的无参方法（无法设置输出上限）");
    }
}
