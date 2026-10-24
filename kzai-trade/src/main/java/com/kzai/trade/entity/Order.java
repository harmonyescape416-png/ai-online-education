package com.kzai.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单表（原始表 `order`，19 个字段，未做任何结构修改）。
 * <p>
 * 状态枚举沿用原文：1待支付，2已支付，3已关闭，4已完成，5已报名，6已申请退款。
 * 金额字段单位统一为「分」（INT）。
 */
@Data
// order 为 MySQL 关键字，表名必须转义
@TableName("`order`")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 支付交易流水号；本阶段未接入真实支付，保持为空 */
    private Long payOrderNo;

    private Long userId;

    private Integer status;

    private String message;

    /** 订单总金额，单位分 */
    private Integer totalAmount;

    /** 实付金额，单位分 */
    private Integer realAmount;

    /** 优惠金额，单位分 */
    private Integer discountAmount;

    private String payChannel;

    private String couponIds;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime closeTime;

    private LocalDateTime finishTime;

    private LocalDateTime refundTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    @TableLogic
    private Integer deleted;
}
