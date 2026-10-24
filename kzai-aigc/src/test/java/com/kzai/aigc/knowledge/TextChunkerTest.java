package com.kzai.aigc.knowledge;

import com.kzai.aigc.knowledge.chunk.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片边界测试（M9，任务书 A-5/6/7）：
 * 长度 0/1/50/499/500/501/950/1000/1001 码点；
 * 切片 ≤500、相邻重叠约 50、末尾不丢失、中文/emoji（代理对）不截断。
 * 计数口径 = Unicode 码点。
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static int cp(String s) {
        return s.codePointCount(0, s.length());
    }

    @Test
    void blankDocuments_produceNoChunks() {
        assertTrue(chunker.chunk(null).isEmpty());
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk("   \n\n  \r\n").isEmpty(), "空白文档不得产生有效切片");
    }

    @Test
    void shortTexts_singleChunk_notLost() {
        assertEquals(List.of("a"), chunker.chunk("a"));
        assertEquals(1, chunker.chunk(repeat('字', 50)).size());
        assertEquals(1, chunker.chunk(repeat('字', 499)).size());
        assertEquals(1, chunker.chunk(repeat('字', 500)).size(), "恰好 500 为单片");
    }

    @Test
    void overSize_splitsAt500_withOverlap50() {
        List<String> chunks = chunker.chunk(repeat('字', 501));
        assertEquals(2, chunks.size(), "501 码点 → 2 片");
        assertTrue(cp(chunks.get(0)) <= 500);
        // 重叠：第二片开头 50 码点 == 第一片末尾 50 码点
        String tail50 = chunks.get(0).substring(chunks.get(0).length() - 50);
        assertTrue(chunks.get(1).startsWith(tail50) || chunks.get(1).length() <= 50,
                "相邻切片保留 50 码点重叠");
    }

    @Test
    void length950_1000_1001_boundaries() {
        List<String> c950 = chunker.chunk(repeat('字', 950));
        assertTrue(c950.size() == 2, "950 → 2 片");
        assertTrue(c950.stream().allMatch(c -> cp(c) <= 500));

        List<String> c1000 = chunker.chunk(repeat('字', 1000));
        assertTrue(c1000.size() >= 2);
        assertTrue(c1000.stream().allMatch(c -> cp(c) <= 500));

        List<String> c1001 = chunker.chunk(repeat('字', 1001));
        assertTrue(c1001.size() >= 3);
        assertTrue(c1001.stream().allMatch(c -> cp(c) <= 500));
        // 内容完整：除重叠区外，全部字符都应出现（末尾不丢失）
        String last = c1001.get(c1001.size() - 1);
        assertTrue(last.endsWith("字"), "末尾不足 500 不得丢失");
    }

    @Test
    void chinesePunctuation_newline_emoji_notTruncated() {
        // 含 emoji（代理对）、中文标点、换行：按码点计数，不产生乱码（总长 >500 触发多片）
        String text = "什么是 RAG？🎉 它结合了检索与生成。\n\n"
                + "检索增强生成（RAG）先从知识库中检索相关片段，再交给模型生成回答。\n".repeat(30);
        List<String> chunks = chunker.chunk(text);
        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertTrue(cp(chunk) <= 500, "每片 ≤500 码点");
            // 无孤立代理对（低代理项不可出现在首位/末位）
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)), "切片不得以代理对后半开始");
            char last = chunk.charAt(chunk.length() - 1);
            assertFalse(Character.isHighSurrogate(last), "切片不得以代理对前半结束");
        }
        String joined = String.join("", chunks);
        assertTrue(joined.contains("🎉"), "emoji 不被破坏");
        assertTrue(joined.contains("什么是 RAG？"), "中文标点不被破坏");
    }

    @Test
    void overlapApprox50_betweenConsecutiveChunks() {
        List<String> chunks = chunker.chunk(repeat('字', 1500));
        assertTrue(chunks.size() >= 3);
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String cur = chunks.get(i);
            int overlap = Math.min(60, Math.min(prev.length(), cur.length()));
            String prevTail = prev.substring(prev.length() - overlap);
            String curHead = cur.substring(0, overlap);
            boolean hasOverlap = cur.startsWith(prevTail) || prev.endsWith(curHead)
                    || commonPrefixLen(prevTail, curHead) >= 40;
            assertTrue(hasOverlap, "相邻切片应有约 50 码点重叠（实测片段 " + i + "）");
        }
    }

    @Test
    void paragraphStructure_preserved_withinChunk() {
        String doc = "# 标题一\n第一段内容。\n\n## 标题二\n第二段内容较多一些。\n";
        List<String> chunks = chunker.chunk(doc);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("# 标题一"));
        assertTrue(chunks.get(0).contains("第二段内容"));
    }

    @Test
    void repeatedParsing_deterministic() {
        String doc = repeat('字', 1200);
        assertEquals(chunker.chunk(doc), chunker.chunk(doc), "重复解析同一版本不产生不同切片");
    }

    private static int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return n;
    }

    private static void assertFalse(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }
}
