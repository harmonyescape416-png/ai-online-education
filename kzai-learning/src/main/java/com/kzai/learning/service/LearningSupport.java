package com.kzai.learning.service;

import com.kzai.common.constant.Constants;

/** 学习状态与展示语义（沿用原文枚举，不做重定义）。 */
public final class LearningSupport {

    private LearningSupport() {
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Constants.LEARN_STATUS_NOT_STARTED -> "未开始";
            case Constants.LEARN_STATUS_LEARNING -> "学习中";
            case Constants.LEARN_STATUS_FINISHED -> "已完成";
            default -> "未知";
        };
    }

    public static String sourceText(String source) {
        if (source == null) {
            return "无权益";
        }
        return switch (source) {
            case Constants.ENTITLEMENT_FREE -> "免费课程";
            case Constants.ENTITLEMENT_PAID -> "已购买";
            default -> "无权益";
        };
    }

    /** 进度百分比：总章节为 0 时按 0 处理，绝不返回 100 冒充完成 */
    public static int percent(long finished, long total) {
        if (total <= 0) {
            return 0;
        }
        long value = finished * 100 / total;
        return (int) Math.max(0, Math.min(100, value));
    }
}
