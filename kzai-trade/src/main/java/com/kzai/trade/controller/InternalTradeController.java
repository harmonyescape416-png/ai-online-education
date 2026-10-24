package com.kzai.trade.controller;

import com.kzai.common.result.R;
import com.kzai.trade.dto.EntitlementDecision;
import com.kzai.trade.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 交易服务内部接口：供用户服务（删除前关联校验）与课程服务（学习权益校验）调用。
 * 网关拒绝对外暴露 /internal/**，且必须携带内部签名。
 */
@RestController
@RequestMapping("/internal/trade")
@RequiredArgsConstructor
public class InternalTradeController {

    private final OrderService orderService;

    /** 用户订单数：大于 0 即禁止删除该用户 */
    @GetMapping("/orders/count-by-user/{userId}")
    public R<Long> countByUser(@PathVariable("userId") Long userId) {
        return R.ok(orderService.countByUser(userId));
    }

    /** 是否已取得该课程的学习权益（订单状态 + 明细状态 + 退款状态 + 有效期） */
    @GetMapping("/entitlements")
    public R<Boolean> hasPaidEntitlement(@RequestParam("userId") Long userId,
                                         @RequestParam("courseId") Long courseId) {
        return R.ok(orderService.hasPaidEntitlement(userId, courseId));
    }

    /** 结构化权益判定（M5）：给出来源、原因、到期时间、退款状态，供学习服务明确提示 */
    @GetMapping("/entitlements/detail")
    public R<EntitlementDecision> entitlementDetail(@RequestParam("userId") Long userId,
                                                    @RequestParam("courseId") Long courseId) {
        return R.ok(orderService.entitlementDetail(userId, courseId));
    }

    /** 批量判定已购课程ID */
    @GetMapping("/paid-courses")
    public R<Set<Long>> paidCourseIds(@RequestParam("userId") Long userId,
                                      @RequestParam("courseIds") List<Long> courseIds) {
        return R.ok(orderService.paidCourseIds(userId, courseIds));
    }
}
