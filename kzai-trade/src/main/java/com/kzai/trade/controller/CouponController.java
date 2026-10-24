package com.kzai.trade.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.context.UserContext;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.trade.coupon.CouponAppService;
import com.kzai.trade.coupon.CouponQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/coupons")
@RequireRoles({Constants.ROLE_STUDENT, Constants.ROLE_ADMIN})
@RequiredArgsConstructor
public class CouponController {

    private final CouponAppService coupons;
    private final CouponQuoteService quotes;

    @GetMapping("/center")
    public R<List<Map<String, Object>>> center() {
        return R.ok(coupons.center(UserContext.getUserId()));
    }

    @PostMapping("/{id}/claim")
    public R<Map<String, Object>> claim(@PathVariable("id") Long id) {
        return R.ok(coupons.claim(UserContext.getUserId(), id));
    }

    @PostMapping("/redeem")
    public R<Map<String, Object>> redeem(@RequestBody Map<String, Object> body) {
        Object code = body == null ? null : body.get("code");
        return R.ok(coupons.redeem(UserContext.getUserId(), code == null ? "" : String.valueOf(code)));
    }

    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        return R.ok(coupons.mine(UserContext.getUserId()));
    }

    @GetMapping("/mine/{id}")
    public R<Map<String, Object>> mineOne(@PathVariable("id") Long id) {
        return R.ok(coupons.mineOne(UserContext.getUserId(), id));
    }

    @PostMapping("/mine/{id}/prices")
    public R<Map<String, Object>> prices(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("courseIds");
        List<Long> courseIds = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    courseIds.add(Long.valueOf(String.valueOf(item)));
                }
            }
        }
        return R.ok(quotes.prices(UserContext.getUserId(), id, courseIds));
    }

    @PostMapping("/quote")
    public R<Map<String, Object>> quote(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("courseIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new com.kzai.common.exception.BizException("课程ID列表不能为空");
        }
        List<Long> courseIds = new ArrayList<>();
        for (Object item : list) {
            courseIds.add(Long.valueOf(String.valueOf(item)));
        }
        return R.ok(quotes.quote(UserContext.getUserId(), courseIds));
    }
}
