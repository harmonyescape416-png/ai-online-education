package com.kzai.trade.dto;

import lombok.Data;

/** 我的订单查询条件。 */
@Data
public class OrderQuery {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 订单状态：1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款 */
    private Integer status;

    /** 排序：createTimeDesc（默认）/ createTimeAsc */
    private String sort;
}
