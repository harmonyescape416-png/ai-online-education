package com.kzai.learning.points;

import com.kzai.learning.stats.StatsWindow;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class PointClock {
    private Clock clock = Clock.system(StatsWindow.ZONE);

    public void use(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public String period(LocalDate date) {
        return date.getYear() + "-" + String.format("%02d", date.getMonthValue());
    }
}
