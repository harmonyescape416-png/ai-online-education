package com.kzai.trade.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习权益判定结果（交易服务内部契约，M5）。
 * <p>
 * 判定完全依据 MySQL 中真实的订单与订单明细：
 * 订单归属 → 订单状态 → 明细状态 → 退款状态 → 学习有效期。
 * 不接受调用方传入的 paid / orderId / 任意状态值。
 */
@Data
public class EntitlementDecision {

    /** 是否具备学习权益 */
    private Boolean allowed;
    /** 权益来源：PAID（已支付订单明细）/ NONE */
    private String source;
    /** 不具备权益时面向用户的明确原因 */
    private String reason;
    /** 权益到期时间，NULL 表示长期有效 */
    private LocalDateTime expireTime;
    /** 命中的订单ID */
    private Long orderId;
    /** 命中的订单明细状态 */
    private Integer detailStatus;
    /** 命中的退款状态 */
    private Integer refundStatus;
    /** 交易服务是否可用（恒为 true，供调用方区分"不可用"与"无权益"） */
    private Boolean tradeAvailable;

    public static EntitlementDecision deny(String reason) {
        EntitlementDecision d = new EntitlementDecision();
        d.setAllowed(false);
        d.setSource("NONE");
        d.setReason(reason);
        d.setTradeAvailable(Boolean.TRUE);
        return d;
    }

    public static EntitlementDecision grant(Long orderId, Integer detailStatus,
                                            Integer refundStatus, LocalDateTime expireTime) {
        EntitlementDecision d = new EntitlementDecision();
        d.setAllowed(true);
        d.setSource("PAID");
        d.setReason("已取得学习权益");
        d.setOrderId(orderId);
        d.setDetailStatus(detailStatus);
        d.setRefundStatus(refundStatus);
        d.setExpireTime(expireTime);
        d.setTradeAvailable(Boolean.TRUE);
        return d;
    }
}
