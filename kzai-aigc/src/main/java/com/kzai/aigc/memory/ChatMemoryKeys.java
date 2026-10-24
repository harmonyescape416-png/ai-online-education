package com.kzai.aigc.memory;

/**
 * Redis Key 常量（系统设计说明书 4.2 / db.md 645）。
 * <p>
 *  - CHAT:{conversationId}      会话消息 Redis List（系统设计说明书 5.5.3 / 7 天 TTL）
 *  - AI:STOP:{sessionId}        停止生成标识（5 分钟 TTL）
 *  - AI:CHAN:{sessionId}        同一会话同时只允许一个生成任务的应用内锁占位
 * <p>
 * conversationId = userId + "_" + sessionId
 */
public final class ChatMemoryKeys {

    public static final String CHAT_PREFIX = "CHAT:";
    public static final String STOP_PREFIX = "AI:STOP:";
    public static final String LOCK_PREFIX = "AI:CHAN:";
    public static final String DUP_PREFIX = "AI:DUP:";

    private ChatMemoryKeys() {}

    public static String conversationId(long userId, String sessionId) {
        return userId + "_" + sessionId;
    }

    public static String chatKey(long userId, String sessionId) {
        return CHAT_PREFIX + conversationId(userId, sessionId);
    }

    public static String stopKey(String sessionId) {
        return STOP_PREFIX + sessionId;
    }

    public static String lockKey(String sessionId) {
        return LOCK_PREFIX + sessionId;
    }

    /** M15.15：同会话连续重复文本防护 key */
    public static String dupKey(long userId, String sessionId) {
        return DUP_PREFIX + userId + ":" + sessionId;
    }
}
