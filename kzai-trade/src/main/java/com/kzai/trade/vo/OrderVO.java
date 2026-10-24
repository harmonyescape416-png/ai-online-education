package com.kzai.trade.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单列表项。 */
@Data
@Builder
public class OrderVO {

    private Long id;
    private Long userId;
    private Integer status;
    private String statusText;
    /** 总金额，单位分 */
    private Integer totalAmount;
    private BigDecimal totalAmountYuan;
    private Integer realAmount;
    private BigDecimal realAmountYuan;
    private Integer discountAmount;
    private String message;
    private Integer itemCount;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime closeTime;
    /** 该订单上每笔退款申请的处理结果。没有退款时为空列表。 */
    private List<OrderRefundNoticeVO> refundNotices;
}
