package com.kzai.aigc.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话消息 JSON 序列化（M7）。
 * <p>
 * 存储格式（与原始设计一致：Redis List，每条消息一个 List 元素）：
 * <pre>{"type":"USER","content":"..."}</pre>
 * 助手消息可携带工具卡片（M7 1003 事件的历史回放数据）：
 * <pre>{"type":"ASSISTANT","content":"...","cards":[{"type":"COURSE_CARD","payload":{...}}]}</pre>
 * 兼容：M6 曾用 String 存 JSON 数组 <code>[{...},{...}]</code>，
 * {@link #parseLegacyArray} 仍可解析旧格式，由 {@link RedisChatMemory} 完成无损迁移。
 * <p>
 * 消息正文仅存 Redis，不落 chat_session 表。
 */
@Component
@RequiredArgsConstructor
public class ChatMessageCodec {

    private final ObjectMapper objectMapper;

    /** 单条消息 → 一行 JSON（不含卡片） */
    public String serialize(Message message) {
        return serializeEntry(typeOf(message), contentOf(message), null);
    }

    /** 单条消息（可带卡片）→ 一行 JSON；卡片为空时不输出 cards 字段 */
    public String serializeEntry(String type, String content, List<Map<String, Object>> cards) {
        return serializeEntry(type, content, cards, null);
    }

    /**
     * M13：助手消息可附带本轮路由 agent 标识（可空），
     * 供监管「按意图筛选」；旧消息无该字段（null），监管如实显示未知。
     */
    public String serializeEntry(String type, String content, List<Map<String, Object>> cards, String agent) {
        try {
            Dto dto = new Dto(type, content);
            if (cards != null && !cards.isEmpty()) {
                dto.cards = cards;
            }
            if (agent != null && !agent.isBlank()) {
                dto.agent = agent;
            }
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息序列化失败", e);
        }
    }

    /** 旧格式（M6 String 存 JSON 数组）→ 消息列表；null/空 返回可变空列表 */
    public List<Dto> parseLegacyArray(String json) {
        List<Dto> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            List<Dto> dtos = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Dto.class));
            if (dtos != null) {
                dtos.stream().filter(java.util.Objects::nonNull).forEach(result::add);
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("旧格式消息反序列化失败", e);
        }
    }

    /** 便捷：旧格式数组直接转 Message 列表 */
    public List<Message> legacyToMessages(String json) {
        List<Message> messages = new ArrayList<>();
        for (Dto dto : parseLegacyArray(json)) {
            Message m = toMessage(dto);
            if (m != null) {
                messages.add(m);
            }
        }
        return messages;
    }

    /** 一行 JSON → 消息正文（卡片不进入模型上下文） */
    public Message toMessage(String entryJson) {
        return toMessage(parseEntry(entryJson));
    }

    /** 一行 JSON → 完整条目（含卡片），供会话详情历史回放 */
    public Dto parseEntry(String entryJson) {
        if (entryJson == null || entryJson.isBlank()) {
            return null;
        }
        try {
            Dto dto = objectMapper.readValue(entryJson, Dto.class);
            // 极端容错：若误存了旧格式数组字符串，取首条
            if (dto == null || (dto.type == null && dto.content == null && entryJson.trim().startsWith("["))) {
                List<Dto> legacy = parseLegacyArray(entryJson);
                return legacy.isEmpty() ? null : legacy.get(0);
            }
            return dto;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息反序列化失败", e);
        }
    }

    private Message toMessage(Dto dto) {
        if (dto == null) {
            return null;
        }
        String type = dto.type == null ? "USER" : dto.type.toUpperCase();
        return switch (type) {
            case "SYSTEM" -> new SystemMessage(dto.content == null ? "" : dto.content);
            case "ASSISTANT" -> new AssistantMessage(dto.content == null ? "" : dto.content);
            default -> new UserMessage(dto.content == null ? "" : dto.content);
        };
    }

    private String typeOf(Message m) {
        return m.getMessageType().name();
    }

    private String contentOf(Message m) {
        // Spring AI 1.0.0-M6：Message extends Content，取文本是 getText()（1.0 正式版才是 getContent()）
        return m.getText() == null ? "" : m.getText();
    }

    public static class Dto {
        public String type;
        public String content;
        /** M7：助手消息关联的工具卡片（1003 载荷），普通消息为 null */
        public List<Map<String, Object>> cards;
        /** M13：本轮路由 agent 标识（RECOMMEND/CONSULT/BUY/KNOWLEDGE/OTHER）；旧消息为 null */
        public String agent;

        public Dto() {}
        public Dto(String type, String content) {
            this.type = type;
            this.content = content;
        }
    }
}
