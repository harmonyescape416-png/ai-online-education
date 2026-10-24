package com.kzai.learning.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import com.kzai.learning.dto.EntitlementDecision;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 交易服务内部接口（M5）：学习权益判定。
 * 学习服务不访问订单表，权益一律由交易服务依据真实订单判定；交易服务不可用时按无权益处理。
 */
@FeignClient(name = "kzai-trade", path = "/internal/trade",
        configuration = InternalAuthFeignConfig.class)
public interface TradeClient {

    @GetMapping("/entitlements")
    R<Boolean> hasPaidEntitlement(@RequestParam("userId") Long userId,
                                  @RequestParam("courseId") Long courseId);

    /** 结构化权益判定：包含来源、原因、到期时间、退款状态，供学习服务给出明确提示 */
    @GetMapping("/entitlements/detail")
    R<EntitlementDecision> entitlementDetail(@RequestParam("userId") Long userId,
                                             @RequestParam("courseId") Long courseId);
}
