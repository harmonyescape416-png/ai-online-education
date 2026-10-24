package com.kzai.trade.controller;

import com.kzai.common.constant.Constants;
import com.kzai.common.result.R;
import com.kzai.common.security.RequireRoles;
import com.kzai.trade.coupon.CouponAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/coupons")
@RequireRoles({Constants.ROLE_ADMIN, Constants.ROLE_STAFF})
@RequiredArgsConstructor
public class CouponAdminController {

    private final CouponAppService coupons;

    @GetMapping
    public R<java.util.List<Map<String, Object>>> list() {
        return R.ok(coupons.adminList());
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(coupons.create(body));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(coupons.updateDraft(id, body));
    }

    @PostMapping("/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable("id") Long id) {
        return R.ok(coupons.publish(id));
    }

    @PostMapping("/{id}/stop")
    public R<Map<String, Object>> stop(@PathVariable("id") Long id) {
        return R.ok(coupons.stop(id));
    }
}
