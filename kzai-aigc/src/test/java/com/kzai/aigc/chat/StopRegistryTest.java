package com.kzai.aigc.chat;

import com.kzai.aigc.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 停止生成注册表单元测试（M6）。 */
class StopRegistryTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private StopRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        AiProperties props = new AiProperties();
        props.setStopTtlMinutes(5);
        registry = new StopRegistry(redis, props);
    }

    @Test
    void sameSessionOnlyOneActiveTask() {
        assertTrue(registry.tryAcquire("s1"));
        assertFalse(registry.tryAcquire("s1"), "同一会话并发生成必须被拒绝");
        registry.release("s1");
        assertTrue(registry.tryAcquire("s1"), "释放后应可再次生成");
    }

    @Test
    void differentSessionsDoNotBlockEachOther() {
        assertTrue(registry.tryAcquire("s1"));
        assertTrue(registry.tryAcquire("s2"));
    }

    @Test
    void markAndClearStop() {
        Mockito.when(redis.hasKey("AI:STOP:s1")).thenReturn(Boolean.TRUE);
        registry.markStop("s1");
        Mockito.verify(ops).set("AI:STOP:s1", "1", Duration.ofMinutes(5));
        assertTrue(registry.isStopRequested("s1"));
        registry.clearStop("s1");
        Mockito.verify(redis).delete("AI:STOP:s1");
    }

    @Test
    void stopFlagDoesNotLeakToNextRound() {
        // 第 1 轮：标记停止 → 结束清理 → 第 2 轮不应被误伤
        Mockito.when(redis.hasKey("AI:STOP:s5")).thenReturn(Boolean.TRUE);
        registry.markStop("s5");
        assertTrue(registry.isStopRequested("s5"));
        registry.clearStop("s5");
        Mockito.when(redis.hasKey("AI:STOP:s5")).thenReturn(Boolean.FALSE);
        assertFalse(registry.isStopRequested("s5"), "清理后新一轮生成不得被旧标识误伤");
    }

    @Test
    void redisDown_doesNotThrow() {
        Mockito.when(redis.hasKey(Mockito.anyString())).thenThrow(new RuntimeException("down"));
        assertFalse(registry.isStopRequested("s9"));
    }
}
