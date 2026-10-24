package com.kzai.trade.service;

import com.kzai.common.constant.Constants;

import java.util.List;
import java.util.Set;

/** 订单状态语义（沿用原文枚举，不做重定义）。 */
public final class OrderSupport {

    /** 视为"已取得学习权益/已付款"的订单状态：2已支付，4已完成，5已报名 */
    public static final Set<Integer> PAID_ORDER_STATUSES =
            Set.of(Constants.ORDER_STATUS_PAID, Constants.ORDER_STATUS_FINISHED, Constants.ORDER_STATUS_ENROLLED);

    /** 明细侧同理（原文 order_detail.status 枚举 1~5） */
    public static final Set<Integer> PAID_DETAIL_STATUSES =
            Set.of(Constants.ORDER_STATUS_PAID, Constants.ORDER_STATUS_FINISHED, Constants.ORDER_STATUS_ENROLLED);

    private OrderSupport() {
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Constants.ORDER_STATUS_PENDING -> "待支付";
            case Constants.ORDER_STATUS_PAID -> "已支付";
            case Constants.ORDER_STATUS_CLOSED -> "已关闭";
            case Constants.ORDER_STATUS_FINISHED -> "已完成";
            case Constants.ORDER_STATUS_ENROLLED -> "已报名";
            case Constants.ORDER_STATUS_REFUNDING -> "已申请退款";
            default -> "未知";
        };
    }

    public static boolean paid(Integer status) {
        return status != null && PAID_ORDER_STATUSES.contains(status);
    }

    public static List<Integer> paidStatusList() {
        return List.copyOf(PAID_ORDER_STATUSES);
    }

    // ---------------------------------------------------------------- M5 权益判定语义

    /**
     * 退款状态是否阻断学习权益。
     * <p>
     * 原始《数据库设计说明书》3.3.7 只写了"退款状态"四个字，未定义枚举。
     * 按"未定义清楚的状态一律不得默认授予权益"补全（decisions.md D5-3）：
     * NULL / 0 无退款、3 退款驳回 → 保留权益；1 退款申请中、2 已退款 → 收回权益；
     * 其余未知值 → 收回权益。
     */
    public static boolean refundBlocksEntitlement(Integer refundStatus) {
        if (refundStatus == null || refundStatus == 0
                || refundStatus == Constants.REFUND_REJECTED
                || refundStatus == Constants.REFUND_FAILED) {
            return false;
        }
        return true;
    }

    public static String refundStatusText(Integer refundStatus) {
        if (refundStatus == null) {
            return "无退款";
        }
        return switch (refundStatus) {
            case Constants.REFUND_NONE -> "无退款";
            case Constants.REFUND_APPLYING -> "退款申请中";
            case Constants.REFUND_SUCCESS -> "已退款";
            case Constants.REFUND_REJECTED -> "退款已驳回";
            case Constants.REFUND_FAILED -> "退款失败";
            default -> "退款状态未知";
        };
    }
}
