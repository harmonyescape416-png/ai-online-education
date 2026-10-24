package com.kzai.aigc.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具结果暂存单元测试（M7）：去重、有序、隔离。 */
class ToolResultHolderTest {

    @Test
    void addCard_dedupByCardId_keepsOrder() {
        ToolResultHolder h = new ToolResultHolder(3, "s1", "req-1");
        assertTrue(h.addCard("COURSE_CARD", "course:1", Map.of("name", "A")));
        assertTrue(h.addCard("COURSE_CARD", "course:2", Map.of("name", "B")));
        // 同一实体重复（模型循环/重连/更完整载荷）不得产生第二张卡片；后写合并覆盖
        assertTrue(h.addCard("COURSE_CARD", "course:1", Map.of("chapterCount", 8)));
        assertTrue(h.addCard("ORDER_CARD", "order:9", Map.of("orderId", "9")));

        List<Map<String, Object>> cards = h.cards();
        assertEquals(3, cards.size(), "同 cardId 只保留一张卡片");
        assertEquals("COURSE_CARD", cards.get(0).get("type"));
        assertEquals("COURSE_CARD", cards.get(1).get("type"));
        assertEquals("ORDER_CARD", cards.get(2).get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) cards.get(0).get("payload");
        assertEquals("A", merged.get("name"), "旧载荷保留");
        assertEquals(8, merged.get("chapterCount"), "新载荷覆盖/补充");
        // 位置保持：course:1 仍在首位
        assertEquals("course:1", cards.get(0).get("cardId"));
    }

    @Test
    void isolation_perInstance() {
        // 不同轮次实例互不可见：无全局静态状态
        ToolResultHolder a = new ToolResultHolder(3, "s1", "r1");
        ToolResultHolder b = new ToolResultHolder(7, "s2", "r2");
        a.addCard("COURSE_CARD", "course:1", Map.of("name", "A"));
        assertTrue(b.cards().isEmpty(), "不同用户/会话/轮次的结果不得串流");
        assertEquals(1, a.cards().size());
    }

    @Test
    void usageCollection_nullSafe() {
        ToolResultHolder h = new ToolResultHolder(1, "s", "r");
        h.collectUsage(null);
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 12;
            }

            @Override
            public Integer getCompletionTokens() {
                return 34;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        h.collectUsage(usage);
        assertEquals(12, h.getPromptTokens());
        assertEquals(34, h.getCompletionTokens());
        assertEquals(46, h.getTotalTokens());
    }
}
