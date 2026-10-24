package com.kzai.aigc.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 消息编解码单元测试（M7：单条目 List 格式 + 卡片 + 旧格式兼容）。 */
class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    @Test
    void entryRoundTrip_keepsOrderAndTypes() {
        String userLine = codec.serialize(new UserMessage("第一问"));
        String systemLine = codec.serialize(new SystemMessage("你是助手"));
        String assistantLine = codec.serialize(new AssistantMessage("第一答"));

        assertEquals("USER", codec.parseEntry(userLine).type);
        assertEquals("第一问", codec.parseEntry(userLine).content);
        assertEquals("SYSTEM", codec.parseEntry(systemLine).type);
        assertEquals("ASSISTANT", codec.parseEntry(assistantLine).type);
        // 单条目 JSON 不应是数组
        assertTrue(userLine.trim().startsWith("{"));
    }

    @Test
    void entryWithCards_roundTrip() {
        Map<String, Object> payload = Map.of("courseId", "123", "name", "Java 基础", "price", 19900);
        String line = codec.serializeEntry("ASSISTANT", "已为你找到课程",
                List.of(Map.of("type", "COURSE_CARD", "payload", payload)));

        ChatMessageCodec.Dto dto = codec.parseEntry(line);
        assertEquals("ASSISTANT", dto.type);
        assertEquals("已为你找到课程", dto.content);
        assertEquals(1, dto.cards.size());
        assertEquals("COURSE_CARD", dto.cards.get(0).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) dto.cards.get(0).get("payload");
        assertEquals("Java 基础", back.get("name"));
    }

    @Test
    void entryWithoutCards_hasNullCards() {
        ChatMessageCodec.Dto dto = codec.parseEntry(codec.serialize(new UserMessage("q")));
        assertNull(dto.cards);
    }

    @Test
    void legacyArrayFormat_stillParsable() {
        // M6 旧格式：String 存 JSON 数组
        String legacy = "[{\"type\":\"USER\",\"content\":\"旧问题\"},{\"type\":\"ASSISTANT\",\"content\":\"旧回答\"}]";
        List<Message> messages = codec.legacyToMessages(legacy);
        assertEquals(2, messages.size());
        assertEquals("USER", messages.get(0).getMessageType().name());
        assertEquals("旧问题", messages.get(0).getText());
        assertEquals("ASSISTANT", messages.get(1).getMessageType().name());
    }

    @Test
    void utf8ChineseAndEmojiSurvive() {
        String text = "中文测试 🎉 — 混合内容\n第二行";
        ChatMessageCodec.Dto dto = codec.parseEntry(codec.serialize(new UserMessage(text)));
        assertEquals(text, dto.content);
    }

    @Test
    void blankOrNullReturnsEmpty() {
        assertTrue(codec.parseLegacyArray(null).isEmpty());
        assertTrue(codec.legacyToMessages("  ").isEmpty());
    }
}
