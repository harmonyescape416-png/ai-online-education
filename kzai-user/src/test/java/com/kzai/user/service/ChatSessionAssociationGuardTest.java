package com.kzai.user.service;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.user.client.AigcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChatSessionAssociationGuard} 单元测试（M6）。
 * <p>
 * 重点：服务不可用必须返回负数（fail-closed，由 UserManageService 拒绝删除），
 * 不得返回 0 放行；正常情况返回真实会话数；不跨服务访问 chat_session 表。
 */
class ChatSessionAssociationGuardTest {

    private AigcClient aigcClient;
    private ChatSessionAssociationGuard guard;

    @BeforeEach
    void setUp() {
        aigcClient = mock(AigcClient.class);
        guard = new ChatSessionAssociationGuard(aigcClient);
    }

    @Test
    void metadata() {
        assertEquals("AI 会话", guard.bindingName());
        assertEquals(Constants.ASSOC_CHAT_SESSION, guard.bindingType());
    }

    @Test
    void returnsRealCounts() {
        when(aigcClient.countSessionsByUser(anyLong())).thenReturn(R.ok(counts(3L)));
        assertEquals(3L, guard.count(42L));
    }

    @Test
    void zeroWhenNoSessions() {
        when(aigcClient.countSessionsByUser(anyLong())).thenReturn(R.ok(counts(0L)));
        assertEquals(0L, guard.count(42L));
    }

    @Test
    void negativeWhenServiceUnavailable() {
        when(aigcClient.countSessionsByUser(anyLong()))
                .thenThrow(new IllegalStateException("aigc service down"));
        long c = guard.count(42L);
        assertTrue(c < 0, "服务不可用必须返回负数以触发 fail-closed，实际=" + c);
    }

    @Test
    void negativeWhenResponseHasNoData() {
        when(aigcClient.countSessionsByUser(anyLong())).thenReturn(R.ok(null));
        long c = guard.count(42L);
        assertTrue(c < 0, "内部响应缺 data 时必须返回负数，实际=" + c);
    }

    private static Map<String, Object> counts(Long n) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", "42");
        m.put("count", n);
        return m;
    }
}
