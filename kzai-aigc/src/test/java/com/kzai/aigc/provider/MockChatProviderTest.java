package com.kzai.aigc.provider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MOCK Provider 单元测试（M6）。 */
class MockChatProviderTest {

    private final MockChatProvider provider = new MockChatProvider();

    @Test
    void identity_isMock() {
        assertEquals("MOCK", provider.code());
        assertTrue(provider.mock());
    }

    @Test
    void call_returnsMarkedReply() {
        String reply = provider.call(List.of(new UserMessage("你好啊")));
        assertTrue(reply.contains("【MOCK】"), "必须显著标识 MOCK");
        assertTrue(reply.contains("非真实模型"), "必须声明非真实模型");
        assertTrue(reply.contains("你好啊"), "应回显用户问题");
    }

    @Test
    void stream_emitsMultipleIncrementsInOrder() {
        Flux<String> flux = provider.stream(List.of(new UserMessage("流式")));
        List<String> chunks = flux.collectList().block();
        assertFalse(chunks == null || chunks.isEmpty());
        assertTrue(chunks.size() > 1, "流式应产生多个增量: " + chunks.size());
        String joined = String.join("", chunks);
        assertTrue(joined.contains("【MOCK】".substring(0, 1)) || joined.contains("MOCK"));
    }

    @Test
    void stream_handlesEmptyHistory() {
        List<String> chunks = provider.stream(List.<Message>of()).collectList().block();
        assertFalse(chunks == null || chunks.isEmpty());
    }
}
