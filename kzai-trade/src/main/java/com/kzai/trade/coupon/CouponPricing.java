package com.kzai.trade.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 每单一张券。折扣按支付比例的万分比计算，分位四舍五入（HALF_UP）。
 * 优惠只分摊到适用课程，各明细优惠之和等于订单优惠。
 */
public final class CouponPricing {

    private CouponPricing() {
    }

    public record Line(long courseId, int price, Long detailId) {
    }

    public record Share(long courseId, Long detailId, int price, int discount, int payable) {
    }

    public record Decision(boolean usable, String reason, int discount, int payable, List<Share> shares) {
        public static Decision no(String reason, int total) {
            return new Decision(false, reason, 0, total, List.of());
        }
    }

    public static int discountOf(String type, int eligible, int reduceAmount, int payRatio) {
        if (eligible <= 0) {
            return 0;
        }
        if ("FULL_REDUCTION".equals(type)) {
            return Math.min(Math.max(reduceAmount, 0), eligible);
        }
        int ratio = Math.min(Math.max(payRatio, 0), 10000);
        int payable = BigDecimal.valueOf(eligible)
                .multiply(BigDecimal.valueOf(ratio))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(0, eligible - payable);
    }

    public static int[] allocate(int[] prices, int discount) {
        int[] shares = new int[prices.length];
        int base = 0;
        for (int price : prices) {
            base += Math.max(price, 0);
        }
        if (base <= 0 || discount <= 0) {
            return shares;
        }
        int used = 0;
        long[] remainder = new long[prices.length];
        for (int i = 0; i < prices.length; i++) {
            long prod = (long) Math.max(prices[i], 0) * discount;
            shares[i] = (int) (prod / base);
            remainder[i] = prod % base;
            used += shares[i];
        }
        Integer[] order = new Integer[prices.length];
        for (int i = 0; i < prices.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingLong((Integer i) -> remainder[i]).reversed().thenComparingInt(i -> i));
        for (int i = 0; i < discount - used; i++) {
            shares[order[i]]++;
        }
        return shares;
    }

    public static Decision decide(String type, int threshold, int reduceAmount, int payRatio, String scope,
                                  String courseIds, LocalDateTime validStart, LocalDateTime validEnd,
                                  List<Line> lines, LocalDateTime now) {
        int total = lines.stream().mapToInt(Line::price).sum();
        if (now.isBefore(validStart)) {
            return Decision.no("未到使用时间", total);
        }
        if (!now.isBefore(validEnd)) {
            return Decision.no("已过期", total);
        }
        List<Line> eligible = new ArrayList<>();
        for (Line line : lines) {
            if (inScope(scope, courseIds, line.courseId())) {
                eligible.add(line);
            }
        }
        if (eligible.isEmpty()) {
            return Decision.no("不在适用课程范围", total);
        }
        int base = eligible.stream().mapToInt(Line::price).sum();
        if (base < threshold) {
            return Decision.no("未达到最低消费", total);
        }
        int discount = discountOf(type, base, reduceAmount, payRatio);
        if (discount <= 0) {
            return Decision.no("优惠金额为 0", total);
        }
        int[] shares = allocate(eligible.stream().mapToInt(Line::price).toArray(), discount);
        List<Share> result = new ArrayList<>();
        int cursor = 0;
        for (Line line : lines) {
            int lineDiscount = 0;
            if (inScope(scope, courseIds, line.courseId())) {
                lineDiscount = shares[cursor++];
            }
            result.add(new Share(line.courseId(), line.detailId(), line.price(), lineDiscount, line.price() - lineDiscount));
        }
        return new Decision(true, "", discount, total - discount, result);
    }

    public static boolean inScope(String scope, String courseIds, long courseId) {
        if (!"INCLUDE".equals(scope)) {
            return true;
        }
        if (courseIds == null || courseIds.isBlank()) {
            return false;
        }
        for (String part : courseIds.split(",")) {
            if (part.equals(String.valueOf(courseId))) {
                return true;
            }
        }
        return false;
    }
}
