package com.kzai.learning.stats;

import com.kzai.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsWindowTest {

    @Test
    void blankDatesAreCumulative() {
        assertEquals(StatsWindow.Mode.CUMULATIVE, StatsWindow.parse(null, "  ").mode());
    }

    @Test
    void oneSideMissingIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> StatsWindow.parse("2026-10-01", ""));
        assertEquals("开始日期和结束日期必须同时填写", ex.getMessage());
    }

    @Test
    void illegalAndReversedDatesAreRejected() {
        assertThrows(BizException.class, () -> StatsWindow.parse("2026/10/01", "2026-10-02"));
        BizException reversed = assertThrows(BizException.class, () -> StatsWindow.parse("2026-10-03", "2026-10-01"));
        assertEquals("开始日期不能晚于结束日期", reversed.getMessage());
    }

    @Test
    void rangeIsHalfOpenAtNextMidnight() {
        StatsWindow window = StatsWindow.parse("2026-10-01", "2026-10-02");
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), window.startInclusive());
        assertEquals(LocalDateTime.of(2026, 10, 3, 0, 0), window.endExclusive());
    }

    @Test
    void businessDateUsesShanghai() {
        LocalDateTime utcEvening = LocalDateTime.of(2026, 10, 1, 16, 30)
                .atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        LocalDate day = StatsWindow.businessDate(utcEvening);
        assertEquals(LocalDate.of(2026, 10, 2), day);
    }
}
