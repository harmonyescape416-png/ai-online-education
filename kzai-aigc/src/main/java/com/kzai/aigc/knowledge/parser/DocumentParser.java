package com.kzai.aigc.knowledge.parser;

import com.kzai.aigc.knowledge.entity.KnowledgeDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 知识文档解析器（M9）：PDF（PDFBox 真实文本提取）与 Markdown（原文提取）。
 * <p>
 * 规则：
 * <ul>
 *   <li>只做文本提取与轻量规范化，不生成任何假设内容；</li>
 *   <li>扫描型 PDF（无文本层）→ 明确报「不支持文本提取」，不伪造文本；</li>
 *   <li>文件名不参与任何服务器路径拼接（存储路径由服务端受控生成）。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentParser {

    /** 解析异常：带用户可读原因 */
    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    /**
     * 解析出纯文本。
     *
     * @param docType PDF / MARKDOWN（已按白名单校验）
     * @param bytes   文件原始字节
     */
    public String parse(String docType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ParseException("文件内容为空");
        }
        return switch (docType) {
            case KnowledgeDocument.TYPE_PDF -> parsePdf(bytes);
            case KnowledgeDocument.TYPE_MARKDOWN -> parseMarkdown(bytes);
            default -> throw new ParseException("不支持的文档类型: " + docType);
        };
    }

    private String parsePdf(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.getNumberOfPages() == 0) {
                throw new ParseException("PDF 无有效页面");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null || text.strip().isEmpty()) {
                // 扫描型 PDF 无文本层：如实报告，不生成虚假文本
                throw new ParseException("不支持文本提取：该 PDF 无文本层（可能为扫描件，当前版本不支持 OCR）");
            }
            return text;
        } catch (ParseException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("[knowledge] PDF 解析失败: {}", ex.getMessage());
            throw new ParseException("PDF 解析失败：文件可能已损坏或非合法 PDF");
        }
    }

    private String parseMarkdown(byte[] bytes) {
        // Markdown 提取 = 原文（保留标题/列表结构，切片层负责清洗）；
        // 剥离常见 HTML 注释与 front-matter 分隔，其余不改动，不生成内容
        String md = new String(bytes, StandardCharsets.UTF_8).strip();
        if (md.isEmpty()) {
            throw new ParseException("Markdown 文件内容为空");
        }
        if (md.startsWith("---")) {
            int close = md.indexOf("\n---", 3);
            if (close > 0) {
                md = md.substring(md.indexOf('\n', close + 1)).strip();
            }
        }
        md = md.replaceAll("<!--.*?-->", "").strip();
        if (md.isEmpty()) {
            throw new ParseException("Markdown 文件去除注释后为空");
        }
        return md;
    }

    /** 基本魔数校验：MIME 声明与实际格式一致性（PDF 以 %PDF- 开头） */
    public static boolean looksLikePdf(byte[] bytes) {
        return bytes != null && bytes.length > 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }
}
