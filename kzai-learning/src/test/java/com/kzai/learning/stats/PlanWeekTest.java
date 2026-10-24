package com.kzai.learning.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanWeekTest {

    @Test
    void weekStartsMondayAndExcludesNextMonday() {
        LocalDate sunday = LocalDate.of(2026, 10, 18);
        LocalDate monday = LocalDate.of(2026, 10, 19);
        LocalDate nextMonday = LocalDate.of(2026, 10, 26);
        assertEquals(LocalDate.of(2026, 10, 12), PlanWeek.mondayOf(sunday));
        assertEquals(monday, PlanWeek.mondayOf(monday));
        assertEquals(monday, PlanWeek.mondayOf(LocalDate.of(2026, 10, 25)));
        LocalDate end = PlanWeek.nextMonday(monday);
        assertEquals(nextMonday, end);
        assertTrue(!monday.isBefore(monday) && monday.isBefore(end));
        assertFalse(nextMonday.isBefore(end));
        assertTrue(sunday.isBefore(monday));
    }
}
