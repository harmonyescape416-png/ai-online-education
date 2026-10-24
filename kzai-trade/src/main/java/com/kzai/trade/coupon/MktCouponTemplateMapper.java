package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface MktCouponTemplateMapper extends BaseMapper<MktCouponTemplate> {

    @Update("""
            UPDATE mkt_coupon_template
            SET claimed_count = claimed_count + 1, update_time = #{now}
            WHERE id = #{id} AND status = 'PUBLISHED'
              AND claimed_count < total_stock
              AND claim_start <= #{now} AND claim_end > #{now}
            """)
    int claimOne(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mkt_coupon_template
            SET claimed_count = claimed_count - 1, update_time = #{now}
            WHERE id = #{id} AND claimed_count > 0
            """)
    int giveBackOne(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mkt_coupon_template
            SET used_count = used_count + 1, update_time = #{now}
            WHERE id = #{id}
            """)
    int markUsed(@Param("id") long id, @Param("now") LocalDateTime now);
}
