package com.kzai.aigc.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kzai.aigc.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 会话记忆单元测试（M7）：用内存结构模拟真实 Redis 的 List/String 语义。
 * <p>
 * 覆盖：List 有序追加、lastN 读取、旧 String 格式无损迁移、卡片持久化、
 * TTL 刷新、降级路径、幂等 markOnce、跨会话隔离。
 */
class RedisChatMemoryTest {

    /** 模拟 Redis：list 数据 / string 数据 / TTL 标记共用两个存储 */
    private final Map<String, List<String>> listStore = new ConcurrentHashMap<>();
    private final Map<String, String> stringStore = new ConcurrentHashMap<>();
    private final Map<String, Long> ttlStore = new ConcurrentHashMap<>();

    private StringRedisTemplate redis;
    private RedisChatMemory memory;
    private ValueOperations<String, String> ops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listStore.clear();
        stringStore.clear();
        ttlStore.clear();

        redis = Mockito.mock(StringRedisTemplate.class);
        ops = Mockito.mock(ValueOperations.class);
        ListOperations<String, String> listOps = Mockito.mock(ListOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        Mockito.when(redis.opsForList()).thenReturn(listOps);

        Mockito.when(listOps.rightPush(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String k = inv.getArgument(0);
                    if (stringStore.containsKey(k)) {
                        throw new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value");
                    }
                    listStore.computeIfAbsent(k, x -> java.util.Collections.synchronizedList(new ArrayList<>()))
                            .add(inv.getArgument(1));
                    return (long) listStore.get(k).size();
                });

        Mockito.when(listOps.range(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    String k = inv.getArgument(0);
                    long start = inv.getArgument(1);
                    long end = inv.getArgument(2);
                    List<String> all = listStore.get(k);
                    if (all == null) {
                        return List.of();
                    }
                    int size = all.size();
                    int s = (int) (start < 0 ? Math.max(0, size + start) : start);
                    int e = (int) (end < 0 ? size + end : Math.min(end, size - 1));
                    if (s > e || s >= size) {
                        return List.of();
                    }
                    return new ArrayList<>(all.subList(s, e + 1));
                });

        Mockito.when(ops.setIfAbsent(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.<Duration>any()))
                .thenAnswer(inv -> {
                    String k = inv.getArgument(0);
                    if (stringStore.containsKey(k) || listStore.containsKey(k)) {
                        return Boolean.FALSE;
                    }
                    stringStore.put(k, inv.getArgument(1));
                    return Boolean.TRUE;
                });
        Mockito.when(ops.get(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> stringStore.get(inv.getArgument(0)));

        Mockito.when(redis.expire(ArgumentMatchers.anyString(), ArgumentMatchers.<Duration>any()))
                .thenAnswer(inv -> {
                    ttlStore.put(inv.getArgument(0), System.currentTimeMillis());
                    return true;
                });
        Mockito.when(redis.delete(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            boolean existed = listStore.remove(k) != null || stringStore.remove(k) != null;
            return existed;
        });
        Mockito.when(redis.type(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            if (listStore.containsKey(k)) {
                return DataType.LIST;
            }
            if (stringStore.containsKey(k)) {
                return DataType.STRING;
            }
            return DataType.NONE;
        });

        AiProperties props = new AiProperties();
        props.setMemoryTtlHours(168);
        memory = new RedisChatMemory(redis, new ChatMessageCodec(new ObjectMapper()), props);
    }

    private ChatMessageCodec.Dto dtoOf(String line) {
        return new ChatMessageCodec(new ObjectMapper()).parseEntry(line);
    }

    private List<ChatMessageCodec.Dto> entries(String key) {
        List<ChatMessageCodec.Dto> out = new ArrayList<>();
        for (String line : listStore.getOrDefault(key, List.of())) {
            ChatMessageCodec.Dto d = dtoOf(line);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    @Test
    void add_appendsToRedisListInOrder() {
        memory.add("3_s1", List.of(new UserMessage("问1"), new AssistantMessage("答1")));
        memory.add("3_s1", List.of(new UserMessage("问2")));

        assertTrue(memory.isListStorage("3_s1"), "存储必须是 Redis List");
        List<ChatMessageCodec.Dto> all = entries("CHAT:3_s1");
        assertEquals(3, all.size(), "第二次 add 必须追加而不是覆盖历史");
        assertEquals("问1", all.get(0).content);
        assertEquals("答1", all.get(1).content);
        assertEquals("问2", all.get(2).content);
        assertTrue(ttlStore.containsKey("CHAT:3_s1"), "写入后必须刷新 TTL");
    }

    @Test
    void get_returnsLastN() {
        memory.add("3_s2", List.of(new UserMessage("a"), new UserMessage("b"), new UserMessage("c")));
        List<Message> last2 = memory.get("3_s2", 2);
        assertEquals(2, last2.size());
        assertEquals("b", last2.get(0).getText());
        assertEquals("c", last2.get(1).getText());
    }

    @Test
    void legacyStringFormat_migratedLosslesslyToList() {
        // 预置 M6 旧格式：String 存 JSON 数组
        stringStore.put("CHAT:3_legacy",
                "[{\"type\":\"USER\",\"content\":\"旧问题\"},{\"type\":\"ASSISTANT\",\"content\":\"旧回答\"}]");

        // 读取：旧数据必须可读（不删除旧会话消息）
        List<Message> read = memory.get("3_legacy", 100);
        assertEquals(2, read.size());
        assertEquals("旧问题", read.get(0).getText());

        // 读取触发无损迁移：类型变 List，条目一致
        assertTrue(memory.isListStorage("3_legacy"), "读取旧格式后应无损迁移为 List");
        List<ChatMessageCodec.Dto> migrated = entries("CHAT:3_legacy");
        assertEquals(2, migrated.size());
        assertEquals("旧问题", migrated.get(0).content);
        assertEquals("旧回答", migrated.get(1).content);

        // 迁移后可继续追加
        memory.add("3_legacy", List.of(new UserMessage("新问题")));
        assertEquals(3, entries("CHAT:3_legacy").size());
    }

    @Test
    void legacyStringFormat_writePathAlsoMigrates() {
        stringStore.put("CHAT:3_legacy2",
                "[{\"type\":\"USER\",\"content\":\"历史消息不能丢\"}]");
        // 写路径遇到 WRONGTYPE 自动迁移后重试
        memory.add("3_legacy2", List.of(new UserMessage("新消息")));
        assertTrue(memory.isListStorage("3_legacy2"));
        List<ChatMessageCodec.Dto> all = entries("CHAT:3_legacy2");
        assertEquals(2, all.size());
        assertEquals("历史消息不能丢", all.get(0).content);
        assertEquals("新消息", all.get(1).content);
    }

    @Test
    void appendAssistant_persistsCards() {
        memory.add("3_s4", List.of(new UserMessage("q")));
        Map<String, Object> payload = Map.of("courseId", "1", "price", 19900);
        memory.appendAssistant("3_s4", "已找到课程",
                List.of(Map.of("type", "COURSE_CARD", "payload", payload)));

        List<ChatMessageCodec.Dto> all = entries("CHAT:3_s4");
        assertEquals(2, all.size());
        assertEquals("ASSISTANT", all.get(1).type);
        assertEquals("已找到课程", all.get(1).content);
        assertEquals("COURSE_CARD", all.get(1).cards.get(0).get("type"));

        // 卡片不进入模型上下文：get 只返回正文
        List<Message> ctx = memory.get("3_s4", 100);
        assertEquals(2, ctx.size());
        assertTrue(ctx.get(1).getText().contains("已找到课程"));
    }

    @Test
    void clear_deletesKeyRegardlessOfType() {
        memory.add("3_s3", List.of(new UserMessage("q")));
        assertTrue(memory.isListStorage("3_s3"));
        memory.clear("3_s3");
        assertFalse(memory.isListStorage("3_s3"));
        assertTrue(memory.get("3_s3", 10).isEmpty(), "删除后不应能再读回消息");

        stringStore.put("CHAT:3_s3b", "[{\"type\":\"USER\",\"content\":\"old\"}]");
        memory.clear("3_s3b");
        assertTrue(memory.get("3_s3b", 10).isEmpty());
    }

    @Test
    void isolation_byConversationId() {
        memory.add("3_sa", List.of(new UserMessage("A 的内容")));
        memory.add("7_sa", List.of(new UserMessage("B 的内容")));
        List<Message> a = memory.get("3_sa", 10);
        List<Message> b = memory.get("7_sa", 10);
        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertTrue(a.get(0).getText().contains("A 的内容"));
        assertTrue(b.get(0).getText().contains("B 的内容"));
    }

    @Test
    void add_fallsBackToMemoryWhenRedisDown() {
        Mockito.when(listOpsOf().rightPush(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("redis down"));
        Mockito.when(ops.get(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("redis down"));
        Mockito.when(redis.type(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("redis down"));
        memory.add("3_s5", List.of(new UserMessage("q")));
        assertTrue(memory.isDegraded("3_s5"), "Redis 不可用时应降级为内存缓存而非报错");
        List<Message> back = memory.get("3_s5", 10);
        assertFalse(back.isEmpty(), "降级期间仍可读回内存消息");
    }

    private ListOperations<String, String> listOpsOf() {
        return redis.opsForList();
    }

    @Test
    void markOnce_idempotent() {
        assertTrue(memory.markOnce("m-1", Duration.ofMinutes(5)));
        assertFalse(memory.markOnce("m-1", Duration.ofMinutes(5)), "同一 messageId 第二次必须返回 false");
    }

    @Test
    void entries_onMissingKeyReturnsEmpty() {
        assertTrue(memory.entries("3_none", 100).isEmpty());
    }
}
