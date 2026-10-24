package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("mkt_coupon_scope")
public class MktCouponScope {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long templateId;
    private Long courseId;
}
