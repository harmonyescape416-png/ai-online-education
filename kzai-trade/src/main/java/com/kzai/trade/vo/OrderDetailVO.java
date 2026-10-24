package com.kzai.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单详情（含明细）。 */
@Data
@Builder
public class OrderDetailVO {

    private Long id;
    private Long userId;
    private Integer status;
    private String statusText;
    private Integer totalAmount;
    private BigDecimal totalAmountYuan;
    private Integer realAmount;
    private BigDecimal realAmountYuan;
    private Integer discountAmount;
    private String message;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime closeTime;

    private List<OrderItemVO> items;
}
