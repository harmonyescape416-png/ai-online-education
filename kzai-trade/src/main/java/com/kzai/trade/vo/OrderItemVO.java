package com.kzai.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 订单明细（课程价格快照）。 */
@Data
@Builder
public class OrderItemVO {

    private Long id;
    private Long courseId;
    private String name;
    private String coverUrl;
    /** 下单时价格快照，单位分 */
    private Integer price;
    private BigDecimal priceYuan;
    private Integer validDuration;
    private Integer status;
    private String statusText;
    /** 2 表示该明细退款已成功。 */
    private Integer refundStatus;
}
