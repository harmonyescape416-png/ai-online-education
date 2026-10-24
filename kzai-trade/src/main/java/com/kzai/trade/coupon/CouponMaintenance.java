package com.kzai.trade.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponMaintenance {

    private final CouponOrderService orders;

    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void retry() {
        orders.retryCompensate();
    }
}
