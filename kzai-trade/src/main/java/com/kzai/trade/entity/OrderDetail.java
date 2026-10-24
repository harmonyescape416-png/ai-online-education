package com.kzai.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单明细表（原始表 `order_detail`，18 个字段，未做任何结构修改）。
 * <p>
 * 保存下单时的课程快照：名称、封面、价格、有效期。
 */
@Data
@TableName("order_detail")
public class OrderDetail {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    private Long userId;

    private Long courseId;

    /** 课程价格快照，单位分 */
    private Integer price;

    /** 课程名称快照 */
    private String name;

    /** 封面快照 */
    private String coverUrl;

    /** 有效期，单位月 */
    private Integer validDuration;

    /** 课程过期时间 */
    private LocalDateTime courseExpireTime;

    /** 折扣金额，单位分 */
    private Integer discountAmount;

    /** 实付金额，单位分 */
    private Integer realPayAmount;

    /** 1待支付，2已支付，3已关闭，4已完成，5已报名 */
    private Integer status;

    /** 退款状态 */
    private Integer refundStatus;

    private String payChannel;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;
}
