package com.kzai.aigc.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 交易服务 Feign 客户端（M7/M9）。
 * <p>
 * 按原始设计直接调用现有业务接口 <code>POST /orders/pre-place</code>（M4）：
 * 课程状态、价格、有效期与是否已购由交易服务基于实时课程数据重新校验，
 * 幂等由 order_request_idempotent 唯一索引保证。
 * 本客户端绝不调用支付接口，也绝不把订单改为已支付。
 */
@FeignClient(name = "kzai-trade", configuration = InternalAuthFeignConfig.class)
public interface TradeClient {

    @GetMapping("/internal/trade/paid-courses")
    R<java.util.Set<Long>> paidCourseIds(@RequestParam("userId") Long userId,
                                        @RequestParam("courseIds") java.util.List<Long> courseIds);

    /** 购买预下单（M4 契约）：同一 requestId 重复提交返回同一订单 */
    @PostMapping("/orders/pre-place")
    R<PrePlaceResult> prePlace(@RequestBody PrePlaceRequest request);

    /** M9：已购权益查询（复用 M4 内部契约，fail-closed 语义由调用方保证） */
    @GetMapping("/internal/trade/entitlements")
    R<Boolean> hasEntitlement(@RequestParam("userId") Long userId,
                              @RequestParam("courseId") Long courseId);
}
