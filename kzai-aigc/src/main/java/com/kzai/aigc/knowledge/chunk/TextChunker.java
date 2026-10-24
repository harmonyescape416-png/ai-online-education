package com.kzai.aigc.knowledge.chunk;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文文本切片器（M9，原始需求 3.2.1.4 / 约束(6)：500 字符切片、50 字符重叠）。
 * <p>
 * 计数口径：<b>Unicode 码点（code point）</b>计数——一个中文汉字、一个英文字母、
 * 一个全角标点均记 1；emoji/代理对按 1 个码点计，绝不按 UTF-16 char 截断。
 * 策略：
 * <ol>
 *   <li>清洗：\r\n 归一为 \n；连续空行折叠；去首尾空白；</li>
 *   <li>自然段优先：按空行分段，段内不足 500 直接成片；</li>
 *   <li>超长段按 500 码点硬切，相邻切片保留 50 码点重叠（重叠区取前片尾部）；</li>
 *   <li>末尾不足 500 仍成片（不丢失）；</li>
 *   <li>清洗后为空白 → 返回空列表（不产生有效切片）。</li>
 * </ol>
 */
@Component
public class TextChunker {

    public static final int CHUNK_SIZE = 500;
    public static final int OVERLAP = 50;

    public String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replace("\r\n", "\n").replace('\r', '\n');
        t = t.replaceAll("\n{3,}", "\n\n").trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 切片；返回按顺序的文本片段（每个 ≤ 500 码点，相邻重叠约 50 码点）。
     * 空白文本返回空列表。
     */
    public List<String> chunk(String raw) {
        String cleaned = clean(raw);
        List<String> out = new ArrayList<>();
        if (cleaned == null) {
            return out;
        }
        String[] paragraphs = cleaned.split("\n");
        StringBuilder pending = new StringBuilder();
        for (String para : paragraphs) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            // 当前待发缓冲 + 新段落若不超过上限则继续合并（保留段落结构）
            if (pending.length() > 0
                    && codePoints(pending) + 1 + codePoints(p) > CHUNK_SIZE) {
                flushPending(pending, out);
            }
            if (pending.length() > 0) {
                pending.append('\n');
            }
            pending.append(p);
            // 超长段：pending 已含整段，先按 500 切出除末尾不足 500 的部分，余量留作 pending
            if (codePoints(pending) > CHUNK_SIZE) {
                String remainder = drainOversize(pending, out);
                pending.setLength(0);
                pending.append(remainder);
            }
        }
        if (codePoints(pending) > 0) {
            out.add(pending.toString());
        }
        return out;
    }

    /** 把 pending 切成若干整 500 片（带 50 重叠），返回最后不足 500 的尾部 */
    private String drainOversize(StringBuilder pending, List<String> out) {
        int[] cps = pending.codePoints().toArray();
        int start = 0;
        while (cps.length - start > CHUNK_SIZE) {
            int end = start + CHUNK_SIZE;
            // 优先在切片尾部附近的换行/句读处断开（向前找最多 80 码点），保持可读性
            int cut = findBreakPoint(cps, start, end);
            out.add(new String(cps, start, cut - start));
            start = cut - OVERLAP; // 50 码点重叠
            if (start < 0) {
                start = 0;
            }
            if (start >= cps.length) {
                break;
            }
        }
        return new String(cps, start, cps.length - start);
    }

    /** 在 [end-80, end] 范围内向前寻找 \n 或句号/问号/感叹号断点；找不到则硬切 */
    private int findBreakPoint(int[] cps, int start, int end) {
        int lower = Math.max(start + CHUNK_SIZE - 80, start + 1);
        for (int i = end - 1; i >= lower; i--) {
            if (cps[i] == '\n') {
                return i;
            }
        }
        for (int i = end - 1; i >= lower; i--) {
            int c = cps[i];
            if (c == '。' || c == '！' || c == '?' || c == '？' || c == '；' || c == ';') {
                return i + 1 <= end ? i + 1 : end;
            }
        }
        return end;
    }

    private void flushPending(StringBuilder pending, List<String> out) {
        if (codePoints(pending) > 0) {
            out.add(pending.toString());
        }
        pending.setLength(0);
    }

    private static int codePoints(CharSequence s) {
        if (s == null) {
            return 0;
        }
        return java.lang.Character.codePointCount(s, 0, s.length());
    }
}
