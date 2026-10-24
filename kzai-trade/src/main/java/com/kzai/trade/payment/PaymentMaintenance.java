package com.kzai.trade.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 待支付超时关闭。与支付成功通知使用条件更新，避免互相覆盖。 */
@Component
@RequiredArgsConstructor
public class PaymentMaintenance {

    private final PaymentAppService payments;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeExpired() {
        payments.closeExpired();
    }
}
