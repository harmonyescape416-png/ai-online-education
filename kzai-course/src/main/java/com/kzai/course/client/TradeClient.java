package com.kzai.course.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * 交易服务内部接口（M4）：学习权益校验（是否已支付购买）。
 */
@FeignClient(name = "kzai-trade", path = "/internal/trade",
        configuration = InternalAuthFeignConfig.class)
public interface TradeClient {

    @GetMapping("/entitlements")
    R<Boolean> hasPaidEntitlement(@RequestParam("userId") Long userId,
                                  @RequestParam("courseId") Long courseId);

    @GetMapping("/paid-courses")
    R<Set<Long>> paidCourseIds(@RequestParam("userId") Long userId,
                               @RequestParam("courseIds") List<Long> courseIds);
}
