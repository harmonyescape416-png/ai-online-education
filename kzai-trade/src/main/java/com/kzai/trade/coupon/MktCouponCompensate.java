package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mkt_coupon_compensate")
public class MktCouponCompensate {
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String CONSUME = "CONSUME";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long userCouponId;
    private String action;
    private String status;
    private String reason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
