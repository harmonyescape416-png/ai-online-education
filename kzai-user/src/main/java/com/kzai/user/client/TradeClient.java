package com.kzai.user.client;

import com.kzai.common.feign.InternalAuthFeignConfig;
import com.kzai.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 交易服务内部接口（M4：删除用户前的订单关联校验）。
 * 遵守微服务边界：用户服务不直接操作订单表。
 */
@FeignClient(name = "kzai-trade", path = "/internal/trade",
        configuration = InternalAuthFeignConfig.class)
public interface TradeClient {

    @GetMapping("/orders/count-by-user/{userId}")
    R<Long> countOrdersByUser(@PathVariable("userId") Long userId);
}
