package com.kzai.learning.service;

import java.util.Map;

/**
 * 通知文案工具（M11）：
 * <ul>
 *   <li>只放<b>摘要</b>，不写入受保护讲义/全文（通知存在 ≠ 拥有业务内容权限）；</li>
 *   <li>MOCK 生成必须保留显著标识，不得显示为真实模型输出；</li>
 *   <li>统一去掉换行与控制字符，前端以纯文本展示（防 XSS 与排版污染）。</li>
 * </ul>
 */
public final class NotificationTexts {

    private NotificationTexts() {
    }

    /** 回复摘要（≤100 字，去换行） */
    public static String summary(String content) {
        String s = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return s.length() <= 100 ? s : s.substring(0, 100) + "…";
    }

    /** 跳转载荷（仅 ID，不放正文） */
    public static String payload(long questionId, long replyId) {
        return "{\"questionId\":\"" + questionId + "\",\"replyId\":\"" + replyId + "\"}";
    }

    /** AI 回复标题：MOCK 必须显著标识 */
    public static String aiReplyTitle(String modelCode) {
        boolean mock = modelCode != null && modelCode.toUpperCase().contains("MOCK");
        return mock ? "AI 已回复你的问题（MOCK 演示）" : "AI 已回复你的问题";
    }

    /** AI 回复正文：MOCK 时明确标注来源 */
    public static String aiReplyContent(String modelCode, String content) {
        boolean mock = modelCode != null && modelCode.toUpperCase().contains("MOCK");
        String prefix = mock ? "[MOCK 演示，非真实模型输出] " : "";
        return prefix + summary(content);
    }

    /** 教师回答标题 */
    public static String teacherReplyTitle() {
        return "教师回答了你的问题";
    }

    /** 课程审核通过标题/正文（course 侧复用同类规则，此处仅保留常量语义） */
    public static Map<String, String> courseApproved(String courseName) {
        String name = courseName == null || courseName.isBlank() ? "你的课程" : courseName;
        return Map.of("title", "课程审核已通过",
                "content", "课程《" + name + "》审核通过，现已上架。");
    }
}
