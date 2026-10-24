package com.kzai.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 预下单结果。 */
@Data
@Builder
public class PrePlaceVO {

    private Long orderId;
    private Long userId;
    private Integer status;
    private String statusText;
    private Integer totalAmount;
    private BigDecimal totalAmountYuan;
    private Integer realAmount;
    private Integer discountAmount;
    private String requestId;
    /** 幂等命中时为 true：返回的是此前已创建的订单 */
    private Boolean idempotentHit;
    private LocalDateTime createTime;
    private List<OrderItemVO> items;
}
