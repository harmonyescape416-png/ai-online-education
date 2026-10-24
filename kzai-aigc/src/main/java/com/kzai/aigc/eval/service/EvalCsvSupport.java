package com.kzai.aigc.eval.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测 CSV 工具（M12）：RFC4180 兼容解析 + Excel 公式注入防护 + UTF-8 BOM。
 * 导出规则与 M5 一致：`= + - @ \t \r` 开头的单元格加前导单引号，双引号转义。
 */
@Component
public class EvalCsvSupport {

    private static final char BOM = '\uFEFF';

    /** 解析 CSV 文本为行（首行=表头）；支持双引号包裹、引号转义、CRLF */
    public List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        String t = text;
        if (!t.isEmpty() && t.charAt(0) == BOM) {
            t = t.substring(1);
        }
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellStarted = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                cellStarted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                cellStarted = true;
            } else if (c == '\r') {
                // 忽略，交给 \n 处理
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                cellStarted = false;
            } else {
                cell.append(c);
                cellStarted = true;
            }
        }
        if (cellStarted || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** 公式注入防护 + 引号转义（M5 D5-13 同口径） */
    public String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\r", " ").replace("\n", " ");
        if (!v.isEmpty()) {
            char first = v.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t') {
                v = "'" + v;
            }
        }
        if (v.contains(",") || v.contains("\"")) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** 导出整表（UTF-8 BOM 前缀，Excel 中文兼容） */
    public String export(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOM);
        sb.append(String.join(",", header.stream().map(this::escape).toList()));
        for (List<String> row : rows) {
            sb.append('\n');
            sb.append(String.join(",", row.stream().map(this::escape).toList()));
        }
        return sb.toString();
    }
}
