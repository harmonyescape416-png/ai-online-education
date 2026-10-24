package com.kzai.aigc.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import com.kzai.aigc.memory.RedisChatMemory;
import com.kzai.aigc.provider.ChatProvider;
import com.kzai.aigc.session.ChatSessionService;
import com.kzai.aigc.session.entity.ChatSession;
import com.kzai.aigc.tools.CourseTools;
import com.kzai.aigc.tools.MockToolScriptService;
import com.kzai.aigc.tools.OrderTools;
import com.kzai.aigc.tools.PurchaseProposalStore;
import com.kzai.aigc.tools.ToolRegistry;
import com.kzai.aigc.tools.ToolResultHolder;
import com.kzai.common.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.function.FunctionCallback;
import reactor.core.publisher.Flux;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * ChatService M7 编排单元测试：SSE 事件顺序（1001→1003→1002）、
 * 卡片去重、停止语义、/chat 与 /chat/text 单飞互斥、终态不重复携带全文。
 */
class ChatServiceStreamTest {
    private com.kzai.aigc.recommendation.RecommendationTools recommendationTools;
    private com.kzai.aigc.prompt.PromptVersionService versions;

    private ChatProvider provider;
    private RedisChatMemory memory;
    private ChatSessionService sessionService;
    private StopRegistry stopRegistry;
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private ChatService service;
    private StringWriter sseBuffer;
    private CourseTools courseTools;
    private OrderTools orderTools;
    private PurchaseProposalStore proposalStore;
    private ToolRegistry toolRegistry;
    private MockToolScriptService mockScript;
    private com.kzai.aigc.agent.RouteAgent routeAgent;
    private com.kzai.aigc.agent.AgentToolPolicy toolPolicy;
    private com.kzai.aigc.prompt.PromptManager promptMgr;
    private AiProperties props;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = Mockito.mock(ChatProvider.class);
        Mockito.when(provider.mock()).thenReturn(true);
        Mockito.when(provider.code()).thenReturn("MOCK");
        Mockito.when(provider.supportsTools()).thenReturn(false);

        memory = Mockito.mock(RedisChatMemory.class);
        Mockito.when(memory.markOnce(anyString(), ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(memory.get(anyString(), anyInt()))
                .thenAnswer(inv -> new ArrayList<Message>(List.of(new UserMessage("问"))));
        Mockito.when(memory.isDegraded(anyString())).thenReturn(false);

        sessionService = Mockito.mock(ChatSessionService.class);
        ChatSession session = new ChatSession();
        session.setSessionId("s1");
        Mockito.when(sessionService.requireOwned(anyLong(), anyString())).thenReturn(session);

        courseTools = Mockito.mock(CourseTools.class);
        Mockito.when(courseTools.callbacks()).thenReturn(List.of());
        orderTools = Mockito.mock(OrderTools.class);
        Mockito.when(orderTools.callbacks()).thenReturn(List.of());
        com.kzai.aigc.tools.KnowledgeTools knowledgeTools =
                Mockito.mock(com.kzai.aigc.tools.KnowledgeTools.class);
        Mockito.when(knowledgeTools.callbacks()).thenReturn(List.of());
        // 停止注册表（Redis 桩：hasKey 由 stopFlag 控制）
        org.springframework.data.redis.core.StringRedisTemplate redis =
                Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        Mockito.when(redis.hasKey(anyString())).thenAnswer(inv -> stopFlag.get());
        Mockito.when(redis.opsForValue())
                .thenReturn(Mockito.mock(org.springframework.data.redis.core.ValueOperations.class));
        stopRegistry = new StopRegistry(redis, new AiProperties());

        // 工具层与 M8 路由组件
        proposalStore = Mockito.mock(PurchaseProposalStore.class);
        recommendationTools=Mockito.mock(com.kzai.aigc.recommendation.RecommendationTools.class);
        var recommendCallback=FunctionCallback.builder().function("recommendCourses",
                (java.util.function.BiFunction<Map,org.springframework.ai.chat.model.ToolContext,String>)(r,c)->"{}").inputType(Map.class).description("test").build();
        Mockito.when(recommendationTools.callbacks()).thenReturn(List.of(recommendCallback));
        versions=Mockito.mock(com.kzai.aigc.prompt.PromptVersionService.class);
        toolRegistry = new ToolRegistry(courseTools, orderTools, knowledgeTools, recommendationTools);
        mockScript = new MockToolScriptService(courseTools, orderTools, knowledgeTools,
                proposalStore, new AiProperties(), new ObjectMapper(), recommendationTools);
        promptMgr = promptManager();
        props = new AiProperties();
        routeAgent = new com.kzai.aigc.agent.RouteAgent(provider, promptMgr, proposalStore, new AiProperties());
        toolPolicy = new com.kzai.aigc.agent.AgentToolPolicy(courseTools, orderTools, knowledgeTools, recommendationTools);

        service = newService(props);
    }

    private ChatService newService(AiProperties p) {
        // M13 每日限额用 Redis：stub 原子计数返回 1（未超限）
        org.springframework.data.redis.core.StringRedisTemplate quotaRedis =
                Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> vOps =
                Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        Mockito.when(quotaRedis.opsForValue()).thenReturn(vOps);
        Mockito.when(vOps.increment(Mockito.anyString())).thenReturn(1L);
        return new ChatService(provider, memory, sessionService, stopRegistry,
                p, new SseEvent(new ObjectMapper()), toolRegistry, mockScript,
                proposalStore, routeAgent, toolPolicy, promptMgr,
                new com.kzai.aigc.config.DynamicAiParams("127.0.0.1:8848",
                        Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                        new ObjectMapper(), p),
                quotaRedis,
                Mockito.mock(com.kzai.aigc.stats.ModelCallLogService.class),
                versions);
    }

    @Test void recommendationHidesPreToolGuessworkAndKeepsOldVersionBinding() throws Exception {
        Mockito.when(provider.mock()).thenReturn(false);Mockito.when(provider.call(any())).thenReturn("RECOMMEND");
        var session=sessionService.requireOwned(3L,"s1");session.setPromptVersionMap("{\"RECOMMEND\":51}");
        Mockito.when(versions.specFromVersionMap(anyString(),Mockito.eq(com.kzai.aigc.agent.AgentTypeEnum.RECOMMEND)))
                .thenReturn(com.kzai.aigc.prompt.AgentPromptSpec.builder().systemPrompt("旧模板：先追问基础预算，再调用 searchCourses").build());
        Mockito.when(provider.streamWithTools(any(),any(),any(),any())).thenAnswer(inv->{
            List<Message> messages=inv.getArgument(0);
            assertEquals(1,messages.stream().filter(m->m instanceof org.springframework.ai.chat.messages.SystemMessage).count());
            assertTrue(messages.get(0).getText().contains("旧模板"));assertTrue(messages.get(0).getText().contains("未限定"));
            ToolResultHolder holder=inv.getArgument(3);
            return Flux.concat(Flux.just("请先回答基础、预算和时间；默认零基础。现在调用工具"),Flux.defer(()->{
                holder.recommendationResult(Map.of("message","暂时没有找到与当前学习目标匹配的可推荐课程。","courses",List.of(),"nextAction","你主要想用它做什么？"));
                return Flux.just("可能因课程没写关键词，现在调用推荐工具");
            }));
        });
        service.streamChat(3L,"s1","我要学 Python","policy-test",mockResponse());
        assertTrue(sseBuffer.toString().contains("暂时没有找到"));assertFalse(sseBuffer.toString().contains("默认零基础"));
        assertFalse(sseBuffer.toString().contains("现在调用"));assertFalse(sseBuffer.toString().contains("没写关键词"));
        assertEquals("{\"RECOMMEND\":51}",session.getPromptVersionMap());assertEquals("OK",lastTerminal().get("code").asText());
    }

    @Test void recommendationExecutesRealFallbackToolIfModelOnlyAsksQuestions() throws Exception {
        Mockito.when(provider.mock()).thenReturn(false);Mockito.when(provider.call(any())).thenReturn("RECOMMEND");
        Mockito.when(provider.streamWithTools(any(),any(),any(),any())).thenReturn(Flux.just("请先填写预算和基础"));
        Mockito.when(recommendationTools.recommend(any(),any())).thenAnswer(inv->{
            var ctx=(org.springframework.ai.chat.model.ToolContext)inv.getArgument(1);
            var holder=(ToolResultHolder)ctx.getContext().get(com.kzai.aigc.tools.ToolContextKeys.ROUND);
            assertEquals("我要学 Python",holder.recommendationUserText());
            holder.recommendationResult(Map.of("message","相关候选已在你的已购或学习列表中。","courses",List.of()));return "{}";
        });
        service.streamChat(3L,"s1","我要学 Python","fallback-test",mockResponse());
        assertFalse(sseBuffer.toString().contains("请先填写"));assertTrue(sseBuffer.toString().contains("已购或学习列表"));
        assertTrue(lastTerminal().get("recommendationServerFallback").asBoolean());
        Mockito.verify(recommendationTools).recommend(any(),any());
    }

    @Test void recommendationUpstreamFailureDoesNotClaimNoCourses() throws Exception {
        Mockito.when(provider.mock()).thenReturn(false);Mockito.when(provider.call(any())).thenReturn("RECOMMEND");
        Mockito.when(provider.streamWithTools(any(),any(),any(),any())).thenReturn(Flux.error(new IllegalStateException("upstream unavailable")));
        service.streamChat(3L,"s1","我要学 Python","failure-test",mockResponse());
        assertFalse(sseBuffer.toString().contains("未找到课程"));assertEquals("ERROR",lastTerminal().get("code").asText());
        Mockito.verify(recommendationTools,Mockito.never()).recommend(any(),any());
    }

    private com.kzai.aigc.prompt.PromptManager promptManager() {
        com.kzai.aigc.prompt.NacosPromptGateway gateway =
                Mockito.mock(com.kzai.aigc.prompt.NacosPromptGateway.class);
        org.springframework.data.redis.core.StringRedisTemplate redis =
                Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        return new com.kzai.aigc.prompt.PromptManager(gateway, redis, new ObjectMapper());
    }

    private HttpServletResponse mockResponse() {
        sseBuffer = new StringWriter();
        HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
        try {
            Mockito.when(resp.getWriter()).thenReturn(new PrintWriter(sseBuffer, true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return resp;
    }

    /** 解析缓冲区中的事件码序列 */
    private List<Integer> codes() throws Exception {
        List<Integer> out = new ArrayList<>();
        for (String line : sseBuffer.toString().split("\n")) {
            if (line.startsWith("event: ")) {
                out.add(Integer.parseInt(line.substring(7).trim()));
            }
        }
        return out;
    }

    private JsonNode lastTerminal() throws Exception {
        String[] frames = sseBuffer.toString().split("\n\n");
        for (int i = frames.length - 1; i >= 0; i--) {
            for (String line : frames[i].split("\n")) {
                if (line.startsWith("data: ")) {
                    JsonNode n = new ObjectMapper().readTree(line.substring(6));
                    if (n.path("data").path("terminal").asBoolean(false)) {
                        return n.get("data");
                    }
                }
            }
        }
        return null;
    }

    @Test
    void plainChat_sequence_1001s_thenSingle1002() throws Exception {
        Mockito.when(provider.stream(any())).thenReturn(Flux.just("你", "好", "呀"));
        HttpServletResponse resp = mockResponse();

        service.streamChat(3L, "s1", "问", "m1", resp);

        List<Integer> cs = codes();
        assertEquals(List.of(1001, 1001, 1001, 1002), cs, "普通对话：3×1001 + 恰好 1×1002，无 1003");
        JsonNode terminal = lastTerminal();
        assertEquals("OK", terminal.get("code").asText());
        assertEquals(3, terminal.get("contentLength").asInt(), "终态只带 contentLength，不重复携带全文");
        assertEquals(0, terminal.get("cardCount").asInt());
        assertEquals("OTHER", terminal.path("agent").asText(), "寒暄/无关应标记 OTHER（直接回复）");
        assertTrue(!terminal.toString().contains("content\":\"你好呀"), "终态不得携带完整文本");
    }

    @Test
    void buyIntent_routeToBuyAgent_terminalCarriesAgentTag() throws Exception {
        // 走真实 MockRouteScript → BUY → MockToolScript 脚本流（工具为 mock，卡片由真实 CourseTools
        // 产出——单测中 mock 工具不产卡片，卡片链路由 CourseToolsTest/e2e 覆盖）
        Mockito.when(courseTools.searchCourses(any(), any()))
                .thenReturn("{\"status\":\"OK\",\"count\":1,\"courses\":[{"
                        + "\"courseId\":\"101\",\"name\":\"Java 基础\",\"priceCents\":19900,"
                        + "\"priceYuan\":\"199.00\",\"purchasable\":true}]}");
        Mockito.when(orderTools.createPreOrder(any(), any()))
                .thenReturn("{\"status\":\"CONFIRM_REQUIRED\",\"course\":{\"courseId\":\"101\","
                        + "\"name\":\"Java 基础\",\"priceYuan\":\"199.00\",\"purchasable\":true}}");

        HttpServletResponse resp = mockResponse();
        service.streamChat(3L, "s1", "帮我购买Java基础", "m-buy", resp);

        JsonNode terminal = lastTerminal();
        assertEquals("BUY", terminal.path("agent").asText(), "购买意图应路由至 BUY Agent");
        assertEquals("OK", terminal.get("code").asText());
        assertTrue(terminal.get("contentLength").asInt() > 0);
    }

    @Test
    void toolChat_cardsBetween1001And1002_deduped() throws Exception {
        // M7 legacy 编排路径（multi-agent 关闭）下验证卡片顺序与去重语义
        AiProperties legacyProps = new AiProperties();
        legacyProps.setMultiAgentEnabled(false);
        service = newService(legacyProps);
        Mockito.when(provider.supportsTools()).thenReturn(true);
        Mockito.when(provider.streamWithTools(any(), any(), any(), any())).thenAnswer(inv -> {
            ToolResultHolder round = inv.getArgument(3);
            round.addCard("COURSE_CARD", "course:101", Map.of("name", "Java 基础"));
            round.addCard("COURSE_CARD", "course:101", Map.of("name", "Java 基础")); // 模型重复调用
            round.addCard("ORDER_CARD", "order:9", Map.of("orderId", "9"));
            return Flux.just("为您找到课程", "，已创建订单");
        });
        HttpServletResponse resp = mockResponse();

        service.streamChat(3L, "s1", "购买 Java 基础课程", "m2", resp);

        List<Integer> cs = codes();
        assertEquals(List.of(1001, 1001, 1003, 1003, 1002), cs,
                "1001 → 1003（去重后 2 张）→ 恰好 1×1002；1003 不得出现在 1002 之后");
        JsonNode terminal = lastTerminal();
        assertEquals(2, terminal.get("cardCount").asInt());
    }

    @Test
    void stop_keepsOnlyEmittedText_singleTerminal() throws Exception {
        Mockito.when(provider.stream(any())).thenReturn(
                Flux.just("第一段。", "第二段。", "第三段。", "第四段。")
                        .doOnNext(s -> {
                            // 上游 doOnNext 先于下游 takeWhile 检查：在第三段置位，
                            // 模拟"第二段输出完成后用户点击停止"
                            if (s.equals("第三段。")) {
                                stopFlag.set(true);
                            }
                        }));
        HttpServletResponse resp = mockResponse();

        service.streamChat(3L, "s1", "长回答", "m3", resp);

        List<Integer> cs = codes();
        assertEquals(1002, cs.get(cs.size() - 1));
        assertEquals(1, cs.stream().filter(c -> c == 1002).count(), "停止后终态仍恰好一个");
        JsonNode terminal = lastTerminal();
        assertEquals("STOPPED", terminal.get("code").asText());
        assertTrue(terminal.get("stopped").asBoolean());
        // 只持久化已输出片段：appendAssistant 收到的内容不包含停止后的文本
        Mockito.verify(memory).appendAssistant(anyString(),
                ArgumentMatchers.eq("第一段。第二段。"), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void singleFlight_streamRejected_singleErrorTerminal() throws Exception {
        assertTrue(stopRegistry.tryAcquire("s1")); // 已有任务
        HttpServletResponse resp = mockResponse();

        service.streamChat(3L, "s1", "并发", "m4", resp);

        List<Integer> cs = codes();
        assertEquals(List.of(1002), cs);
        assertEquals("ERROR", lastTerminal().get("code").asText());
        stopRegistry.release("s1");
    }

    @Test
    void textChat_singleFlight_mutuallyExclusiveWithStream() {
        assertTrue(stopRegistry.tryAcquire("s1"));
        // /chat/text 与 /chat 共享单飞：已有生成任务时文本对话被拒绝（M6 遗留修复）
        BizException ex = assertThrows(BizException.class,
                () -> service.callChat(3L, "s1", "问", "m5"));
        assertTrue(ex.getMessage().contains("已有进行中的生成任务"));
        stopRegistry.release("s1");
        // 释放后可正常对话
        Mockito.when(provider.call(any())).thenReturn("回复");
        assertEquals("回复", service.callChat(3L, "s1", "问", "m6"));
        Mockito.verify(memory).appendAssistant(anyString(), ArgumentMatchers.eq("回复"), ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    void textChat_releasesLockAfter() {
        Mockito.when(provider.call(any())).thenReturn("ok");
        service.callChat(3L, "s1", "问", "m7");
        assertTrue(!stopRegistry.isActive("s1"), "文本对话结束后必须释放单飞锁");
    }
}
