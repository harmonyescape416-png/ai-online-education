package com.kzai.trade.coupon;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class CouponClock {
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    public void use(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
