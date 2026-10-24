package com.kzai.common.util;

/**
 * CSV 导出工具（M5）。
 * <p>
 * 处理两类真实风险：
 * <ul>
 *   <li>公式注入：以 = + - @ 开头的单元格在 Excel 中会被当作公式执行，统一前置单引号；</li>
 *   <li>编码问题：中文在 Excel 中乱码，统一输出 UTF-8 BOM。</li>
 * </ul>
 */
public final class CsvUtils {

    /** UTF-8 BOM，保证 Excel 正确识别中文 */
    public static final String UTF8_BOM = "\uFEFF";

    private CsvUtils() {
    }

    public static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String raw = String.valueOf(value)
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ");
        // 公式注入防护
        if (raw.startsWith("=") || raw.startsWith("+") || raw.startsWith("-")
                || raw.startsWith("@") || raw.startsWith("\u0009")) {
            raw = "'" + raw;
        }
        if (raw.contains(",") || raw.contains("\"")) {
            raw = "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }

    /** 数值单元格：不做公式前缀处理，仅保证非空 */
    public static String number(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String line(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(cells[i]);
        }
        return sb.append("\r\n").toString();
    }
}
