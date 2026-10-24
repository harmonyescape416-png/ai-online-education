package com.kzai.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV 导出安全测试：公式注入防护与中文编码（UTF-8 BOM）。 */
class CsvUtilsTest {

    @Test
    void escape_blocksFormulaInjection() {
        assertTrue(CsvUtils.escape("=1+1").startsWith("'="), "等号开头必须被转义");
        assertTrue(CsvUtils.escape("+SUM(A1)").startsWith("'+"), "加号开头必须被转义");
        assertTrue(CsvUtils.escape("-1+2").startsWith("'-"), "减号开头必须被转义");
        assertTrue(CsvUtils.escape("@IMPORT").startsWith("'@"), "at 符号开头必须被转义");
    }

    @Test
    void escape_quotesCommaAndNewline() {
        assertEquals("\"a,b\"", CsvUtils.escape("a,b"));
        assertEquals("\"a\"\"b\"", CsvUtils.escape("a\"b"));
        assertEquals("a b", CsvUtils.escape("a\nb"), "换行替换为空格，避免破坏 CSV 行结构");
    }

    @Test
    void escape_handlesNull() {
        assertEquals("", CsvUtils.escape(null));
    }

    @Test
    void utf8BomPresent() {
        assertEquals('\uFEFF', CsvUtils.UTF8_BOM.charAt(0), "导出必须带 UTF-8 BOM，避免 Excel 中文乱码");
    }

    @Test
    void line_joinsWithCommaAndCrlf() {
        assertEquals("a,b,c\r\n", CsvUtils.line("a", "b", "c"));
    }
}
