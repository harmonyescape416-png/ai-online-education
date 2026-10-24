package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mkt_order_coupon")
public class MktOrderCoupon {
    public static final String LOCKED = "LOCKED";
    public static final String USED = "USED";
    public static final String RELEASED = "RELEASED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long userCouponId;
    private Integer discountAmount;
    private String allocationJson;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
