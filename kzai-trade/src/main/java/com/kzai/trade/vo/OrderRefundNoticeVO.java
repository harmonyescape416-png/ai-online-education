package com.kzai.trade.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 订单列表上的一笔退款处理结果。
 * 管理员批准或驳回提交成功后即写入退款单；学员下次加载订单列表即可看到。
 */
@Data
@Builder
public class OrderRefundNoticeVO {

    private String refundId;
    private String detailId;
    private String courseName;
    /** 退款单状态：APPLYING、REJECTED、SUCCESS 等。 */
    private String status;
    /** PASSED、REJECTED、PENDING、FAILED。批准完成记为 PASSED，驳回记为 REJECTED。 */
    private String outcome;
    private String statusText;
    /** success、warning、info、danger，供列表标签使用。 */
    private String tag;
    private String message;
    private Integer amount;
}
