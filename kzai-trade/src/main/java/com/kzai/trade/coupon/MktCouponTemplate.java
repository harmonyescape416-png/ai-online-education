package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mkt_coupon_template")
public class MktCouponTemplate {
    public static final String FULL = "FULL_REDUCTION";
    public static final String DISCOUNT = "DISCOUNT";
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String STOPPED = "STOPPED";
    public static final String ALL = "ALL";
    public static final String INCLUDE = "INCLUDE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String couponType;
    private Integer thresholdAmount;
    private Integer reduceAmount;
    private Integer payRatio;
    private Integer totalStock;
    private Integer claimedCount;
    private Integer usedCount;
    private Integer perUserLimit;
    private LocalDateTime claimStart;
    private LocalDateTime claimEnd;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private String courseScope;
    private String redeemCode;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
