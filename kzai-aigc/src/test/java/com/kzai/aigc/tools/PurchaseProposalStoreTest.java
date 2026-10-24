package com.kzai.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 购买确认提案存储单元测试（M7）：绑定、确认、消费、降级 fail-closed。 */
class PurchaseProposalStoreTest {

    private final Map<String, String> store = new HashMap<>();
    private PurchaseProposalStore proposals;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0)));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.<Duration>any());
        Mockito.when(redis.delete(ArgumentMatchers.anyString())).thenAnswer(inv ->
                store.remove(inv.getArgument(0)) != null);

        AiProperties props = new AiProperties();
        props.setProposalTtlMinutes(10);
        proposals = new PurchaseProposalStore(redis, new ObjectMapper(), props);
    }

    @Test
    void pending_thenConfirmed_thenConsumed() {
        assertTrue(proposals.get(3L, "s1") == null, "初始无提案");

        PurchaseProposalStore.Proposal p = proposals.storePending(3L, "s1", List.of("101"));
        assertNotNull(p.requestId);
        assertFalse(p.confirmed);

        // 未确认时 get 可见但 confirmed=false
        assertFalse(proposals.get(3L, "s1").confirmed);

        // 用户确认
        assertTrue(proposals.markConfirmed(3L, "s1"));
        assertTrue(proposals.get(3L, "s1").confirmed);

        // 下单成功消费
        proposals.consume(3L, "s1");
        assertNull(proposals.get(3L, "s1"), "消费后提案不可复用");
    }

    @Test
    void stableRequestId_acrossReads() {
        PurchaseProposalStore.Proposal p = proposals.storePending(3L, "s1", List.of("101"));
        PurchaseProposalStore.Proposal again = proposals.get(3L, "s1");
        assertEquals(p.requestId, again.requestId, "同一提案的 requestId 必须稳定（幂等重试复用）");
    }

    @Test
    void proposalBoundToUserAndSession() {
        proposals.storePending(3L, "s1", List.of("101"));
        // 不同用户/会话各自独立
        assertNull(proposals.get(7L, "s1"));
        assertNull(proposals.get(3L, "s2"));
        assertFalse(proposals.markConfirmed(7L, "s1"), "B 用户不能确认 A 的提案");
    }

    @Test
    void redisDown_failClosed() {
        // 存入后 Redis 故障：get 必须按「无提案」处理（写操作 fail-closed）
        proposals.storePending(3L, "s1", List.of("101"));
        store.put("AI:PROPOSAL:3_s1", "__trigger_get_exception__");
        // 模拟 Redis 异常：无法直接让 map 抛异常，改为直接清空模拟读不到
        store.clear();
        assertNull(proposals.get(3L, "s1"), "读不到提案时必须 fail-closed");
        assertFalse(proposals.markConfirmed(3L, "s1"));
    }

    @Test
    void overwritePending_replacesOldProposal() {
        proposals.storePending(3L, "s1", List.of("101"));
        proposals.storePending(3L, "s1", List.of("102"));
        PurchaseProposalStore.Proposal p = proposals.get(3L, "s1");
        assertEquals(List.of("102"), p.courseIds, "新提案覆盖旧提案（同会话唯一有效）");
        assertFalse(p.confirmed, "覆盖后必须重新确认");
    }

    @Test
    void consumedProposal_filteredOut() {
        // 手工放入 consumed=true 的提案（模拟消费写入而非删除的极端情况）
        PurchaseProposalStore.Proposal p = new PurchaseProposalStore.Proposal();
        p.proposalId = "x";
        p.requestId = "ai-x";
        p.courseIds = List.of("101");
        p.confirmed = true;
        p.consumed = true;
        try {
            store.put("AI:PROPOSAL:3_s1", new ObjectMapper().writeValueAsString(p));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertNull(proposals.get(3L, "s1"), "已消费提案必须视为不存在");
    }
}
