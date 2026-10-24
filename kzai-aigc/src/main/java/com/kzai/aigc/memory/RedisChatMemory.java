package com.kzai.aigc.memory;

import com.kzai.aigc.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.DataType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis ChatMemory（M7：按原始设计恢复 Redis List 存储）。
 * <p>
 * 原始设计（系统设计说明书 5.5.3 / 8.1）：会话消息存 <code>CHAT:{conversationId}</code>，
 * 结构为 Redis <b>List</b>，TTL 7 天。M6 曾误用 String 存 JSON 数组，M7 修复：
 * <ul>
 *   <li>每条消息一个 List 元素（RPUSH 追加，天然保序，写并发安全）；</li>
 *   <li>每次写入后 EXPIRE 刷新 7 天 TTL；</li>
 *   <li>读到位时对旧 String 格式<b>无损迁移</b>为 List（先读出、再加迁移锁、复验类型、
 *       原样重写为 List），绝不删除旧会话消息；</li>
 *   <li>用户消息幂等由 AI:MSG:{messageId} 保证（调用方 markOnce）；</li>
 *   <li>停止生成只追加已实际输出的部分内容；</li>
 *   <li>助手消息可携带工具卡片（1003 载荷），用于历史回放。</li>
 * </ul>
 * Redis 不可用时：所有读写降级为进程内存极简版本（最近 20 条），重启即丢失，
 * 由调用方通过 {@link #isDegraded} 显式告知前端，不冒充已持久化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemory {

    private static final String MIGRATE_LOCK_PREFIX = "AI:MIG:";

    private final StringRedisTemplate redis;
    private final ChatMessageCodec codec;
    private final AiProperties props;

    /** 内存降级缓存：进程级，仅在 Redis 不可用时使用（重启即丢失） */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Deque<Message>> fallback =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FALLBACK_MAX = 20;

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            String key = ChatMemoryKeys.CHAT_PREFIX + conversationId;
            Duration ttl = Duration.ofHours(props.getMemoryTtlHours());
            for (Message m : messages) {
                // RPUSH 单条原子追加；WRONGTYPE（旧格式）时 ensureList 内完成迁移后重试一次
                pushWithCompat(key, codec.serialize(m), ttl);
            }
            redis.expire(key, ttl);
        } catch (RuntimeException ex) {
            log.warn("RedisChatMemory.add 降级为内存缓存: {}", ex.getMessage());
            java.util.Deque<Message> q = fallback.computeIfAbsent(conversationId,
                    k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
            q.addAll(messages);
            while (q.size() > FALLBACK_MAX) {
                q.pollFirst();
            }
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            List<ChatMessageCodec.Dto> entries = readEntries(ChatMemoryKeys.CHAT_PREFIX + conversationId, lastN);
            List<Message> messages = new ArrayList<>(entries.size());
            for (ChatMessageCodec.Dto dto : entries) {
                Message m = codec.toMessage(codec.serializeEntry(dto.type, dto.content, null));
                if (m != null) {
                    messages.add(m);
                }
            }
            return messages;
        } catch (RuntimeException ex) {
            log.warn("RedisChatMemory.get 降级为内存缓存: {}", ex.getMessage());
            java.util.Deque<Message> q = fallback.get(conversationId);
            if (q == null) {
                return List.of();
            }
            List<Message> list = List.copyOf(q);
            if (lastN <= 0 || list.size() <= lastN) {
                return list;
            }
            return list.subList(list.size() - lastN, list.size());
        }
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            redis.delete(ChatMemoryKeys.CHAT_PREFIX + conversationId);
        } catch (RuntimeException ex) {
            log.warn("RedisChatMemory.clear 降级: {}", ex.getMessage());
        } finally {
            fallback.remove(conversationId);
        }
    }

    /**
     * 追加助手消息（可携带工具卡片）。停止生成时只传入已实际输出的内容。
     * 卡片仅用于历史回放与前端渲染，不会进入模型上下文。
     */
    public void appendAssistant(String conversationId, String content, List<Map<String, Object>> cards) {
        appendAssistant(conversationId, content, cards, null);
    }

    /** M13：可携带本轮路由 agent 标识（监管按意图筛选依据；旧数据为 null 兼容） */
    public void appendAssistant(String conversationId, String content, List<Map<String, Object>> cards, String agent) {
        if (conversationId == null) {
            return;
        }
        String safeContent = content == null ? "" : content;
        try {
            String key = ChatMemoryKeys.CHAT_PREFIX + conversationId;
            Duration ttl = Duration.ofHours(props.getMemoryTtlHours());
            pushWithCompat(key, codec.serializeEntry("ASSISTANT", safeContent, cards, agent), ttl);
            redis.expire(key, ttl);
        } catch (RuntimeException ex) {
            log.warn("RedisChatMemory.appendAssistant 降级: {}", ex.getMessage());
            java.util.Deque<Message> q = fallback.computeIfAbsent(conversationId,
                    k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
            q.add(new AssistantMessage(safeContent));
            while (q.size() > FALLBACK_MAX) {
                q.pollFirst();
            }
        }
    }

    /** 兼容 M6 调用语义：追加单条消息（无卡片） */
    public void appendPartial(String conversationId, Message message) {
        if (conversationId == null || message == null) {
            return;
        }
        appendAssistant(conversationId, message.getText(), null);
    }

    /**
     * 读取完整条目（含卡片），供会话详情历史回放。
     * Redis 不可用或数据缺失时返回空列表——调用方据此如实告知「未持久化」。
     */
    public List<ChatMessageCodec.Dto> entries(String conversationId, int lastN) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            return readEntries(ChatMemoryKeys.CHAT_PREFIX + conversationId, lastN);
        } catch (RuntimeException ex) {
            log.warn("RedisChatMemory.entries 降级: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 该会话当前是否处于「降级（未持久化）」状态。 */
    public boolean isDegraded(String conversationId) {
        return conversationId != null && fallback.containsKey(conversationId);
    }

    /**
     * 幂等标记：用于防止请求重试导致用户消息重复写入。
     * 返回 true 表示本次是首次处理，调用方才应写入消息；false 表示已处理过（重试）。
     */
    public boolean markOnce(String token, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("AI:MSG:" + token, "1", ttl));
        } catch (RuntimeException ex) {
            // Redis 不可用时无法保证幂等，返回 true 让主流程继续（宁可重复也不丢消息）
            log.warn("RedisChatMemory.markOnce 不可用: {}", ex.getMessage());
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 内部：List 读写 + 旧格式（String JSON 数组）无损迁移
    // ------------------------------------------------------------------

    /** RPUSH 追加；若 key 仍是旧格式 String（WRONGTYPE），先迁移再重试一次 */
    private void pushWithCompat(String key, String entryJson, Duration ttl) {
        try {
            redis.opsForList().rightPush(key, entryJson);
        } catch (RuntimeException first) {
            if (isWrongType(first) && migrateLegacyIfNeeded(key)) {
                redis.opsForList().rightPush(key, entryJson);
            } else {
                throw first;
            }
        }
        redis.expire(key, ttl);
    }

    /** 读取条目：List 直接 LRANGE；String（旧格式）解析后无损迁移为 List */
    private List<ChatMessageCodec.Dto> readEntries(String key, int lastN) {
        DataType type = redis.type(key);
        if (type == DataType.NONE) {
            return List.of();
        }
        if (type == DataType.STRING) {
            String json = redis.opsForValue().get(key);
            List<ChatMessageCodec.Dto> legacy = codec.parseLegacyArray(json);
            migrateLegacyIfNeeded(key);
            return tail(legacy, lastN);
        }
        if (type == DataType.LIST) {
            long start = lastN > 0 ? -lastN : 0;
            List<String> raw = redis.opsForList().range(key, start, -1);
            List<ChatMessageCodec.Dto> result = new ArrayList<>(raw == null ? 0 : raw.size());
            if (raw != null) {
                for (String line : raw) {
                    ChatMessageCodec.Dto dto = codec.parseEntry(line);
                    if (dto != null) {
                        result.add(dto);
                    }
                }
            }
            return result;
        }
        log.warn("CHAT key 类型异常 type={} key={}", type, key);
        return List.of();
    }

    /**
     * 旧 String 格式 → List 无损迁移。
     * 流程：迁移锁（SETNX 10s）→ 复验类型仍是 STRING → 读取全量 → DEL → 逐条 RPUSH → EXPIRE。
     * 拿不到锁说明并发迁移已在进行，直接返回由对方完成；迁移前复验类型避免覆盖已迁移数据。
     * 任何一步失败都不抛出（保留旧数据可读性优先于迁移完成）。
     */
    private boolean migrateLegacyIfNeeded(String key) {
        String lockKey = MIGRATE_LOCK_PREFIX + key;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(locked)) {
            return DataType.STRING != redis.type(key);
        }
        try {
            if (DataType.STRING != redis.type(key)) {
                return true; // 已被并发迁移
            }
            String json = redis.opsForValue().get(key);
            List<ChatMessageCodec.Dto> legacy = codec.parseLegacyArray(json);
            redis.delete(key);
            Duration ttl = Duration.ofHours(props.getMemoryTtlHours());
            for (ChatMessageCodec.Dto dto : legacy) {
                redis.opsForList().rightPush(key, codec.serializeEntry(dto.type, dto.content, dto.cards));
            }
            if (!legacy.isEmpty()) {
                redis.expire(key, ttl);
            }
            log.info("CHAT 消息存储已从旧格式(String JSON数组)迁移为 List: key={} 条数={}", key, legacy.size());
            return true;
        } catch (RuntimeException ex) {
            log.warn("旧格式迁移失败（保留原数据）: key={} err={}", key, ex.getMessage());
            return false;
        } finally {
            redis.delete(lockKey);
        }
    }

    private List<ChatMessageCodec.Dto> tail(List<ChatMessageCodec.Dto> all, int lastN) {
        if (lastN <= 0 || all.size() <= lastN) {
            return all;
        }
        return all.subList(all.size() - lastN, all.size());
    }

    private boolean isWrongType(RuntimeException ex) {
        Throwable t = ex;
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains("WRONGTYPE")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 供测试/调用方确认当前 key 存储类型（List 应为原生设计的目标结构） */
    public boolean isListStorage(String conversationId) {
        try {
            return Objects.equals(redis.type(ChatMemoryKeys.CHAT_PREFIX + conversationId), DataType.LIST);
        } catch (RuntimeException ex) {
            return false;
        }
    }


    /**
     * M15.15：同会话连续重复文本防护——窗口期内相同文本视为重复（防双击/网络重试产生多条相同历史）。
     * 合法的间隔性重复提问不受影响（窗口仅 {@code windowSec} 秒）。
     *
     * @return true 表示窗口期内重复，应拒绝
     */
    public boolean isDuplicateWithinWindow(long userId, String sessionId, String text, int windowSec) {
        String key = ChatMemoryKeys.dupKey(userId, sessionId);
        String last = redis.opsForValue().get(key);
        boolean dup = text.equals(last);
        redis.opsForValue().set(key, text, java.time.Duration.ofSeconds(windowSec));
        return dup;
    }
}
