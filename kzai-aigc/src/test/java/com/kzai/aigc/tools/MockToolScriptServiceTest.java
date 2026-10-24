package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * MOCK 工具脚本单元测试（M7）。
 * 脚本与真实路径调用完全相同的工具实现；本测试验证意图识别、严格确认短语、
 * 卡片/提案联动与【MOCK】显著标识。脚本化流程不计入真实 Tool Calling 验收。
 */
class MockToolScriptServiceTest {

    private CourseTools courseTools;
    private OrderTools orderTools;
    private KnowledgeTools knowledgeTools;
    private PurchaseProposalStore proposalStore;
    private MockToolScriptService script;
    private ToolContext ctx;
    private ToolResultHolder round;

    @BeforeEach
    void setUp() {
        courseTools = Mockito.mock(CourseTools.class);
        orderTools = Mockito.mock(OrderTools.class);
        knowledgeTools = Mockito.mock(KnowledgeTools.class);
        proposalStore = Mockito.mock(PurchaseProposalStore.class);
        AiProperties props = new AiProperties();
        props.setMockDelayMs(1);
        script = new MockToolScriptService(courseTools, orderTools, knowledgeTools,
                proposalStore, props, new ObjectMapper(), Mockito.mock(com.kzai.aigc.recommendation.RecommendationTools.class));
        round = new ToolResultHolder(3L, "s1", "req-1");
        ctx = new ToolContext(java.util.Map.of(
                ToolContextKeys.USER_ID, 3L,
                ToolContextKeys.ROLES, List.of("STUDENT"),
                ToolContextKeys.REQUEST_ID, "req-1",
                ToolContextKeys.SESSION_ID, "s1",
                ToolContextKeys.ROUND, round));
    }

    private static CourseSearchRequest searchReq(String kw, Integer limit) {
        CourseSearchRequest r = new CourseSearchRequest();
        r.setKeyword(kw);
        r.setLimit(limit);
        return r;
    }

    @Test
    void strictConfirmation_onlyShortPhrases() {
        assertTrue(MockToolScriptService.isStrictConfirmation("确认"));
        assertTrue(MockToolScriptService.isStrictConfirmation("确认购买"));
        assertTrue(MockToolScriptService.isStrictConfirmation("好的"));
        assertTrue(MockToolScriptService.isStrictConfirmation("是的，下单吧"));
        // 带课程描述的新请求不是确认：绝不能因此误触发旧提案下单
        assertFalse(MockToolScriptService.isStrictConfirmation("帮我购买 Java 基础课程"));
        assertFalse(MockToolScriptService.isStrictConfirmation("推荐一些 Spring Boot 课程"));
        assertFalse(MockToolScriptService.isStrictConfirmation(null));
    }

    @Test
    void keywordExtraction_deterministic() {
        assertEquals("Java 基础", MockToolScriptService.extractKeyword("帮我购买Java 基础"));
        assertEquals("spring boot", MockToolScriptService.extractKeyword("有哪些spring boot课程吗？"));
        // 纯寒暄不作为关键词（不会命中任何工具意图）
        assertEquals("你好", MockToolScriptService.extractKeyword("你好"));
    }

    @Test
    void fallbackKeyword_picksLongestCjkToken() {
        assertEquals("缓存核验", MockToolScriptService.fallbackKeyword("缓存核验1792384910"));
        assertEquals("spring", MockToolScriptService.fallbackKeyword("spring boot"));
        assertNull(MockToolScriptService.fallbackKeyword(null));
    }

    @Test
    void searchIntent_callsRealToolAndEmitsMockBrandedText() {
        when(courseTools.searchCourses(any(), ArgumentMatchers.eq(ctx)))
                .thenReturn("{\"status\":\"OK\",\"count\":1,\"courses\":[{"
                        + "\"courseId\":\"101\",\"name\":\"Java 基础\",\"priceCents\":19900,"
                        + "\"priceYuan\":\"199.00\",\"purchasable\":true}]}");

        var flux = script.tryStream("推荐一些Java课程", ctx, 3L, "s1", com.kzai.aigc.agent.AgentTypeEnum.RECOMMEND);
        assertTrue(flux != null, "课程搜索意图应命中脚本");
        StringBuilder sb = new StringBuilder();
        flux.doOnNext(sb::append).blockLast();
        String all = sb.toString();
        assertTrue(all.contains("【MOCK"), "MOCK 回复必须显著标识");
        assertTrue(all.contains("Java 基础"));
        assertTrue(all.contains("199.00"));
    }

    @Test
    void buyIntent_goesThroughRealProposalFlow() {
        when(courseTools.searchCourses(any(), ArgumentMatchers.eq(ctx)))
                .thenReturn("{\"status\":\"OK\",\"count\":1,\"courses\":[{"
                        + "\"courseId\":\"101\",\"name\":\"Java 基础\",\"priceCents\":19900,"
                        + "\"priceYuan\":\"199.00\",\"purchasable\":true}]}");
        when(orderTools.createPreOrder(any(), ArgumentMatchers.eq(ctx)))
                .thenReturn("{\"status\":\"CONFIRM_REQUIRED\",\"course\":{\"courseId\":\"101\","
                        + "\"name\":\"Java 基础\",\"priceYuan\":\"199.00\",\"purchasable\":true}}");

        var flux = script.tryStream("帮我购买Java基础", ctx, 3L, "s1", com.kzai.aigc.agent.AgentTypeEnum.BUY);
        StringBuilder sb = new StringBuilder();
        flux.doOnNext(sb::append).blockLast();
        String all = sb.toString();
        // 走了真实 OrderTools 路径（CONFIRM_REQUIRED）→ 文本请求用户确认
        assertTrue(all.contains("请确认"), "购买意图必须先请求用户确认");
        assertTrue(all.contains("199.00"));
    }

    @Test
    void confirmIntent_executesOrder() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.proposalId = "p1";
        p.requestId = "ai-p1";
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(orderTools.createPreOrder(any(), ArgumentMatchers.eq(ctx)))
                .thenReturn("{\"status\":\"OK\",\"orderId\":\"9007199254740993\","
                        + "\"totalAmountYuan\":\"199.00\",\"idempotentHit\":false}");

        var flux = script.tryStream("确认", ctx, 3L, "s1", com.kzai.aigc.agent.AgentTypeEnum.BUY);
        StringBuilder sb = new StringBuilder();
        flux.doOnNext(sb::append).blockLast();
        String all = sb.toString();
        assertTrue(all.contains("9007199254740993"), "订单号必须来自真实交易服务返回");
        assertTrue(all.contains("待支付"), "必须明确是待支付而非购买成功");
    }

    @Test
    void plainText_returnsNull_fallbackToProvider() {
        assertNull(script.tryStream("你好，介绍一下你自己", ctx, 3L, "s1", null),
                "无工具意图（OTHER 直答不进脚本）时回退普通 MOCK 回复");
    }

    @Test
    void searchIntent_notFound_honestReply() {
        when(courseTools.searchCourses(any(), ArgumentMatchers.eq(ctx)))
                .thenReturn("{\"status\":\"NOT_FOUND\",\"message\":\"未找到相关课程\"}");
        var flux = script.tryStream("找一下不存在的课程", ctx, 3L, "s1", com.kzai.aigc.agent.AgentTypeEnum.RECOMMEND);
        StringBuilder sb = new StringBuilder();
        flux.doOnNext(sb::append).blockLast();
        assertTrue(sb.toString().contains("未找到"));
    }
}
