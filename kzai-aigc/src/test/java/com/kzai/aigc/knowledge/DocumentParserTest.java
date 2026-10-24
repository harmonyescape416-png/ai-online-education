package com.kzai.aigc.knowledge;

import com.kzai.aigc.knowledge.parser.DocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析测试（M9，任务书 A-1/2/4）：真实 PDF 文本提取（PDFBox 现场生成合法 PDF）、
 * Markdown 提取、空/损坏文件明确报错、扫描型 PDF（无文本层）如实报告不支持。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    /** 用 PDFBox 现场生成含真实文本层的 PDF */
    private static byte[] realPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pdf_realTextExtracted() throws Exception {
        String text = "Spring Boot auto-configuration guide for course platform.";
        String extracted = parser.parse("PDF", realPdf(text));
        assertTrue(extracted.contains("Spring Boot auto-configuration"),
                "PDF 文本层必须被真实提取");
    }

    @Test
    void markdown_extractedWithFrontMatterAndComments() {
        String md = "---\ntitle: x\n---\n# 什么是 RAG\n<!-- hidden -->\n检索增强生成说明正文。\n";
        String out = parser.parse("MARKDOWN", md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(out.contains("什么是 RAG"));
        assertTrue(out.contains("检索增强生成说明正文"));
        assertFalse(out.contains("hidden"), "HTML 注释被剥离");
        assertFalse(out.startsWith("---"), "front-matter 被剥离");
    }

    @Test
    void emptyOrCorrupted_explicitErrors() {
        DocumentParser.ParseException empty = assertThrows(DocumentParser.ParseException.class,
                () -> parser.parse("PDF", new byte[0]));
        assertTrue(empty.getMessage().contains("为空"));
        DocumentParserParseExceptionAssert.corruptedPdf(() ->
                parser.parse("PDF", "not-a-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        DocumentParserParseExceptionAssert.emptyMd(() ->
                parser.parse("MARKDOWN", "<!-- -->".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 独立断言辅助（保持测试可读） */
    static class DocumentParserParseExceptionAssert {
        static void corruptedPdf(Runnable r) {
            try {
                r.run();
                throw new AssertionError("损坏 PDF 应明确报错");
            } catch (DocumentParser.ParseException ex) {
                assertTrue(ex.getMessage().contains("解析失败") || ex.getMessage().contains("损坏"));
            }
        }

        static void emptyMd(Runnable r) {
            try {
                r.run();
                throw new AssertionError("空 Markdown 应明确报错");
            } catch (DocumentParser.ParseException ex) {
                assertTrue(ex.getMessage().contains("为空"));
            }
        }
    }

    @Test
    void scannedPdf_noTextLayer_reportsUnsupported_notFakeText() throws Exception {
        // 无文本层的 PDF（只建页面不写字）＝扫描件场景：必须明确"不支持文本提取"
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            DocumentParser.ParseException ex = assertThrows(DocumentParser.ParseException.class,
                    () -> parser.parse("PDF", out.toByteArray()));
            assertTrue(ex.getMessage().contains("不支持文本提取"),
                    "扫描型 PDF 必须如实报告不支持，不得生成虚假文本");
            assertFalse(ex.getMessage().contains(" Spring "), "不得出现编造内容");
        }
    }

    @Test
    void pdfMagicNumber_check() {
        assertTrue(DocumentParser.looksLikePdf("%PDF-1.7 ...".getBytes()));
        assertFalse(DocumentParser.looksLikePdf("<html>".getBytes()));
    }
}
