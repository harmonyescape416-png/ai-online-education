package com.kzai.trade.coupon;

import com.kzai.common.exception.BizException;
import com.kzai.common.result.R;
import com.kzai.trade.client.CourseClient;
import com.kzai.trade.dto.CourseSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponQuoteService {

    private final CourseClient courseClient;
    private final CouponAppService coupons;
    private final CouponClock clock;

    public Map<String, Object> quote(long userId, List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            throw new BizException("课程ID列表不能为空");
        }
        List<Long> ids = courseIds.stream().distinct().toList();
        R<List<CourseSnapshot>> result;
        try {
            result = courseClient.batch(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        } catch (Exception ex) {
            log.error("[trade.coupon] 课程服务不可用 {}", ex.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
        List<CourseSnapshot> snapshots = result == null ? null : result.getData();
        if (snapshots == null) {
            throw new BizException("课程信息获取失败，请稍后重试");
        }
        Map<Long, CourseSnapshot> byId = snapshots.stream()
                .collect(Collectors.toMap(CourseSnapshot::getId, Function.identity(), (a, b) -> a));
        List<CouponPricing.Line> lines = new ArrayList<>();
        for (Long courseId : ids) {
            CourseSnapshot snapshot = byId.get(courseId);
            if (snapshot == null || snapshot.getPrice() == null) {
                throw new BizException("课程不存在：" + courseId);
            }
            lines.add(new CouponPricing.Line(courseId, snapshot.getPrice(), null));
        }
        return coupons.quote(userId, lines);
    }

    public Map<String, Object> prices(long userId, long userCouponId, List<Long> courseIds) {
        Map<String, Object> coupon = coupons.mineOne(userId, userCouponId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", coupon.get("status"));
        result.put("couponType", coupon.get("couponType"));
        result.put("payRatio", coupon.get("payRatio"));
        result.put("reduceAmount", coupon.get("reduceAmount"));
        if (!"可使用".equals(coupon.get("status")) || courseIds == null || courseIds.isEmpty()) {
            result.put("courses", List.of());
            return result;
        }
        List<CouponPricing.Line> lines = linesOf(courseIds);
        List<Map<String, Object>> priced = new ArrayList<>();
        for (CouponPricing.Line line : lines) {
            CouponPricing.Decision decision = CouponPricing.decide(
                    String.valueOf(coupon.get("couponType")),
                    Integer.parseInt(String.valueOf(coupon.get("thresholdAmount"))),
                    Integer.parseInt(String.valueOf(coupon.get("reduceAmount"))),
                    Integer.parseInt(String.valueOf(coupon.get("payRatio"))),
                    String.valueOf(coupon.get("courseScope")),
                    joinIds(coupon.get("courseIds")),
                    asTime(coupon.get("validStart")),
                    asTime(coupon.get("validEnd")),
                    List.of(line),
                    clock.now());
            if (!decision.usable()) {
                continue;
            }
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("courseId", String.valueOf(line.courseId()));
            item.put("originalAmount", line.price());
            item.put("discountAmount", decision.discount());
            item.put("payableAmount", decision.payable());
            item.put("originalYuan", com.kzai.common.util.MoneyUtils.centsToYuan(line.price()));
            item.put("discountYuan", com.kzai.common.util.MoneyUtils.centsToYuan(decision.discount()));
            item.put("payableYuan", com.kzai.common.util.MoneyUtils.centsToYuan(decision.payable()));
            priced.add(item);
        }
        result.put("courses", priced);
        return result;
    }

    private static String joinIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static java.time.LocalDateTime asTime(Object value) {
        if (value instanceof java.time.LocalDateTime time) {
            return time;
        }
        return java.time.LocalDateTime.parse(String.valueOf(value));
    }

    private List<CouponPricing.Line> linesOf(List<Long> courseIds) {
        List<Long> ids = courseIds.stream().distinct().toList();
        R<List<CourseSnapshot>> result;
        try {
            result = courseClient.batch(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        } catch (Exception ex) {
            log.error("[trade.coupon] 课程服务不可用 {}", ex.getMessage());
            throw new BizException("课程服务暂时不可用，请稍后重试");
        }
        List<CourseSnapshot> snapshots = result == null ? List.of() : result.getData();
        if (snapshots == null) {
            snapshots = List.of();
        }
        Map<Long, CourseSnapshot> byId = snapshots.stream()
                .collect(Collectors.toMap(CourseSnapshot::getId, Function.identity(), (a, b) -> a));
        List<CouponPricing.Line> lines = new ArrayList<>();
        for (Long courseId : ids) {
            CourseSnapshot snapshot = byId.get(courseId);
            if (snapshot == null || snapshot.getPrice() == null || snapshot.getPrice() <= 0) {
                continue;
            }
            lines.add(new CouponPricing.Line(courseId, snapshot.getPrice(), null));
        }
        return lines;
    }
}
