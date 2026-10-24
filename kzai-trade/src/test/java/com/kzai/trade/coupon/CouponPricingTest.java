package com.kzai.trade.coupon;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouponPricingTest {

    @Test
    void allocationSumsToDiscount() {
        int[] shares = CouponPricing.allocate(new int[]{100, 200, 300}, 100);
        assertArrayEquals(new int[]{17, 33, 50}, shares);
        assertEquals(100, shares[0] + shares[1] + shares[2]);
    }

    @Test
    void discountRoundsHalfUp() {
        assertEquals(15, CouponPricing.discountOf("DISCOUNT", 100, 0, 8500));
        assertEquals(50, CouponPricing.discountOf("FULL_REDUCTION", 80, 50, 10000));
    }

    @Test
    void boundariesAndScope() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 23, 10, 0);
        LocalDateTime end = start.plusHours(1);
        List<CouponPricing.Line> lines = List.of(new CouponPricing.Line(9L, 1000, 1L));
        assertFalse(CouponPricing.decide("FULL_REDUCTION", 100, 100, 10000, "ALL", "",
                start, end, lines, start.minusSeconds(1)).usable());
        assertTrue(CouponPricing.decide("FULL_REDUCTION", 100, 100, 10000, "ALL", "",
                start, end, lines, start).usable());
        assertFalse(CouponPricing.decide("FULL_REDUCTION", 100, 100, 10000, "ALL", "",
                start, end, lines, end).usable());
        CouponPricing.Decision outside = CouponPricing.decide("FULL_REDUCTION", 0, 100, 10000, "INCLUDE", "8",
                start, end, lines, start);
        assertEquals("不在适用课程范围", outside.reason());
        CouponPricing.Decision low = CouponPricing.decide("FULL_REDUCTION", 2000, 100, 10000, "ALL", "",
                start, end, lines, start);
        assertEquals("未达到最低消费", low.reason());
    }
}
