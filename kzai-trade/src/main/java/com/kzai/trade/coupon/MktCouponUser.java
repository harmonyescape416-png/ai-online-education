package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mkt_coupon_user")
public class MktCouponUser {
    public static final String UNUSED = "UNUSED";
    public static final String LOCKED = "LOCKED";
    public static final String USED = "USED";
    public static final String PUBLIC = "PUBLIC";
    public static final String CODE = "CODE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long templateId;
    private Long userId;
    private Integer slotNo;
    private String source;
    private Integer codeClaim;
    private String name;
    private String couponType;
    private Integer thresholdAmount;
    private Integer reduceAmount;
    private Integer payRatio;
    private String courseScope;
    private String courseIds;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private String status;
    private Long lockOrderId;
    private Long usedOrderId;
    private LocalDateTime claimTime;
    private LocalDateTime updateTime;
}
