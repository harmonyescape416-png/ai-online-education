package com.kzai.learning.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习权益判定结果（交易服务内部契约）。
 * <p>
 * 判定完全在服务端依据真实订单完成：当前用户 + 课程ID + 订单归属 + 订单状态 + 明细状态
 * + 退款状态 + 有效期。前端传入的 paid / orderId / 任意状态值一律不作为依据。
 */
@Data
public class EntitlementDecision {

    /** 是否具备学习权益 */
    private Boolean allowed;
    /** 权益来源：FREE / PAID / NONE */
    private String source;
    /** 不具备权益时的原因（面向用户展示） */
    private String reason;
    /** 权益到期时间，NULL 表示长期有效 */
    private LocalDateTime expireTime;
    /** 命中的订单ID（PAID 时非空） */
    private Long orderId;
    /** 命中的订单明细状态 */
    private Integer detailStatus;
    /** 命中的退款状态 */
    private Integer refundStatus;
    /** 交易服务是否可用（不可用时 allowed 恒为 false，fail-closed） */
    private Boolean tradeAvailable;

    public static EntitlementDecision deny(String reason) {
        EntitlementDecision d = new EntitlementDecision();
        d.setAllowed(false);
        d.setSource("NONE");
        d.setReason(reason);
        d.setTradeAvailable(Boolean.TRUE);
        return d;
    }

    public static EntitlementDecision grant(String source, LocalDateTime expireTime) {
        EntitlementDecision d = new EntitlementDecision();
        d.setAllowed(true);
        d.setSource(source);
        d.setReason("已取得学习权益");
        d.setExpireTime(expireTime);
        d.setTradeAvailable(Boolean.TRUE);
        return d;
    }
}
