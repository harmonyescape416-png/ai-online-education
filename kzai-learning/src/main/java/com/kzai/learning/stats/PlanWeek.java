package com.kzai.learning.stats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/** 学习计划周。Asia/Shanghai，周一为一周的第一天，区间为 [周一, 下周一)。 */
public final class PlanWeek {

    private PlanWeek() {
    }

    public static LocalDate today(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(StatsWindow.ZONE));
    }

    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate nextMonday(LocalDate monday) {
        return monday.plusWeeks(1);
    }

    public static LocalDateTime now(java.time.Clock clock) {
        return LocalDateTime.now(clock.withZone(StatsWindow.ZONE));
    }
}
