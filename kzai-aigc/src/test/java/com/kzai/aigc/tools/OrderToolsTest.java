package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.client.CourseAiItem;
import com.kzai.aigc.client.CourseClient;
import com.kzai.aigc.client.PrePlaceRequest;
import com.kzai.aigc.client.PrePlaceResult;
import com.kzai.aigc.client.TradeClient;
import com.kzai.aigc.chat.StopRegistry;
import com.kzai.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预下单工具单元测试（M7）：确认门禁、身份隔离、幂等、停止保护、失败不冒充成功。
 */
class OrderToolsTest {

    private CourseClient courseClient;
    private TradeClient tradeClient;
    private PurchaseProposalStore proposalStore;
    private StopRegistry stopRegistry;
    private OrderTools tools;
    private ToolResultHolder round;
    private ToolContext ctx;

    /** 可切换的停止标识（模拟 AI:STOP:{sessionId} 是否存在） */
    private final java.util.concurrent.atomic.AtomicBoolean stopFlag =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        courseClient = Mockito.mock(CourseClient.class);
        tradeClient = Mockito.mock(TradeClient.class);
        proposalStore = Mockito.mock(PurchaseProposalStore.class);
        // StopRegistry 依赖 Redis：hasKey 由 stopFlag 控制，markStop 模拟为 no-op 写入
        org.springframework.data.redis.core.StringRedisTemplate redis =
                Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        Mockito.when(redis.hasKey(Mockito.anyString())).thenAnswer(inv -> stopFlag.get());
        Mockito.when(redis.opsForValue())
                .thenReturn(Mockito.mock(org.springframework.data.redis.core.ValueOperations.class));
        stopRegistry = new StopRegistry(redis, new com.kzai.aigc.config.AiProperties());
        tools = new OrderTools(courseClient, tradeClient, proposalStore, stopRegistry,
                new ToolAudit(), new ObjectMapper());
        round = new ToolResultHolder(3L, "s1", "req-1");
        ctx = new ToolContext(Map.of(
                ToolContextKeys.USER_ID, 3L,
                ToolContextKeys.ROLES, List.of("STUDENT"),
                ToolContextKeys.REQUEST_ID, "req-1",
                ToolContextKeys.SESSION_ID, "s1",
                ToolContextKeys.ROUND, round));
    }

    private static CourseAiItem course(String id, String name, int priceCents) {
        CourseAiItem c = new CourseAiItem();
        c.setId(id);
        c.setName(name);
        c.setPrice(priceCents);
        c.setPriceYuan(String.format("%.2f", priceCents / 100.0));
        c.setPurchasable(true);
        return c;
    }

    private static PrePlaceResult order(String orderId, int totalCents, boolean idempotentHit) {
        PrePlaceResult vo = new PrePlaceResult();
        vo.setOrderId(orderId);
        vo.setStatus(1);
        vo.setStatusText("待支付");
        vo.setTotalAmount(totalCents);
        vo.setTotalAmountYuan(String.format("%.2f", totalCents / 100.0));
        vo.setRequestId("ai-prop-1");
        vo.setIdempotentHit(idempotentHit);
        PrePlaceResult.Item item = new PrePlaceResult.Item();
        item.setCourseId("101");
        item.setName("Java 基础");
        item.setPrice(totalCents);
        item.setPriceYuan(String.format("%.2f", totalCents / 100.0));
        vo.setItems(List.of(item));
        return vo;
    }

    private static PreOrderRequest req(String courseId) {
        PreOrderRequest r = new PreOrderRequest();
        r.setCourseId(courseId);
        return r;
    }

    @Test
    void notConfirmed_requiresConfirmation_noOrderCreated() {
        when(proposalStore.get(3L, "s1")).thenReturn(null);
        when(courseClient.aiDetail(101L)).thenReturn(R.ok(course("101", "Java 基础", 19900)));

        JsonNode json = parse(tools.createPreOrder(req("101"), ctx));

        assertEquals("CONFIRM_REQUIRED", json.get("status").asText());
        assertEquals("Java 基础", json.get("course").get("name").asText());
        // 未确认绝不写库
        verify(tradeClient, never()).prePlace(any());
        // 登记待确认提案（绑定 userId+sessionId+courseId）
        verify(proposalStore).storePending(Mockito.eq(3L), Mockito.eq("s1"), Mockito.eq(List.of("101")));
        assertTrue(round.cards().isEmpty());
    }

    @Test
    void confirmed_executesRealPreOrder_withStableRequestId() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.proposalId = "prop-1";
        p.requestId = "ai-prop-1";
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(tradeClient.prePlace(any())).thenReturn(R.ok(order("9007199254740993", 19900, false)));

        JsonNode json = parse(tools.createPreOrder(req("101"), ctx));

        assertEquals("OK", json.get("status").asText());
        assertEquals("9007199254740993", json.get("orderId").asText());
        assertEquals("待支付", json.get("statusText").asText());
        // 稳定 requestId：与提案派生一致，重试复用
        ArgumentCaptor<PrePlaceRequest> captor = ArgumentCaptor.forClass(PrePlaceRequest.class);
        verify(tradeClient).prePlace(captor.capture());
        assertEquals("ai-prop-1", captor.getValue().getRequestId());
        assertEquals(List.of(101L), captor.getValue().getCourseIds());
        // 订单卡片已入暂存
        assertEquals(1, round.cards().size());
        assertEquals("ORDER_CARD", round.cards().get(0).get("type"));
        // 成功后消费提案防复用
        verify(proposalStore).consume(3L, "s1");
    }

    @Test
    void confirmed_differentCourse_doesNotOrder() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(courseClient.aiDetail(102L)).thenReturn(R.ok(course("102", "Spring 实战", 29900)));

        JsonNode json = parse(tools.createPreOrder(req("102"), ctx));

        assertEquals("CONFIRM_REQUIRED", json.get("status").asText(), "确认必须绑定具体课程");
        verify(tradeClient, never()).prePlace(any());
    }

    @Test
    void stopRequested_writeRejected() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        // 模拟已请求停止（AI:STOP:{sessionId} 存在）
        stopFlag.set(true);

        JsonNode json = parse(tools.createPreOrder(req("101"), ctx));

        assertEquals("STOPPED", json.get("status").asText(), "停止生成后不得执行未授权写操作");
        verify(tradeClient, never()).prePlace(any());
        verify(proposalStore, never()).consume(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void tradeFailed_noConsume_retryKeepsSameRequestId() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.proposalId = "prop-1";
        p.requestId = "ai-prop-1";
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(tradeClient.prePlace(any())).thenReturn(R.fail(500, "交易服务超时"));

        JsonNode json = parse(tools.createPreOrder(req("101"), ctx));

        assertEquals("TRADE_ERROR", json.get("status").asText());
        assertEquals("ai-prop-1", json.get("requestId").asText(), "超时后必须沿用原 requestId 查询/重试");
        verify(proposalStore, never()).consume(3L, "s1");
        assertTrue(round.cards().isEmpty(), "失败不得发送假成功卡片");
    }

    @Test
    void tradeFeignThrows_controlledError() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(tradeClient.prePlace(any())).thenThrow(new RuntimeException("read timed out"));

        JsonNode json = parse(tools.createPreOrder(req("101"), ctx));
        assertEquals("TRADE_SERVICE_UNAVAILABLE", json.get("status").asText());
        verify(proposalStore, never()).consume(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void idempotentHit_returnsSameOrder_noDuplicateCard() {
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(3L, "s1")).thenReturn(p);
        when(tradeClient.prePlace(any())).thenReturn(R.ok(order("9007199254740993", 19900, true)));
        when(proposalStore.get(3L, "s1")).thenReturn(p);

        tools.createPreOrder(req("101"), ctx);
        // 同一提案的第二次工具调用（模型重复发起/SSE 重连）→ 幂等命中，同一订单
        JsonNode second = parse(tools.createPreOrder(req("101"), ctx));
        assertEquals("OK", second.get("status").asText());
        assertTrue(second.get("idempotentHit").asBoolean());
        // 卡片按 orderId 去重：同订单只保留一张
        assertEquals(1, round.cards().size());
    }

    @Test
    void crossUser_proposalNotShared() {
        // 用户 B（id=7）拿不到用户 A（id=3）的提案
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.courseIds = List.of("101");
        p.confirmed = true;
        when(proposalStore.get(7L, "s1")).thenReturn(null);
        when(courseClient.aiDetail(101L)).thenReturn(R.ok(course("101", "Java 基础", 19900)));

        ToolContext ctxB = new ToolContext(Map.of(
                ToolContextKeys.USER_ID, 7L,
                ToolContextKeys.ROLES, List.of("STUDENT"),
                ToolContextKeys.REQUEST_ID, "req-B",
                ToolContextKeys.SESSION_ID, "s1",
                ToolContextKeys.ROUND, round));

        JsonNode json = parse(tools.createPreOrder(req("101"), ctxB));
        assertEquals("CONFIRM_REQUIRED", json.get("status").asText(), "用户 A 的确认不能被用户 B 复用");
        verify(proposalStore).storePending(Mockito.eq(7L), Mockito.eq("s1"), Mockito.anyList());
        verify(tradeClient, never()).prePlace(any());
    }

    @Test
    void noIdentity_refused() {
        ToolContext anon = new ToolContext(Map.of(
                ToolContextKeys.REQUEST_ID, "r",
                ToolContextKeys.SESSION_ID, "s1"));
        JsonNode json = parse(tools.createPreOrder(req("101"), anon));
        assertEquals("NO_IDENTITY", json.get("status").asText(), "无可信身份必须拒绝");
        verify(tradeClient, never()).prePlace(any());
    }

    @Test
    void neverTrustModelSuppliedPriceOrStatus() {
        // 模型入参只有 courseId：PrePlaceRequest 无价格/状态字段（类型系统层面防伪造）
        PrePlaceRequest r = new PrePlaceRequest(List.of(101L), "ai-x");
        assertNotNull(r.getRequestId());
        assertEquals(List.of(101L), r.getCourseIds());
        assertFalse(new PrePlaceRequest().toString().contains("price"));
    }

    private JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
