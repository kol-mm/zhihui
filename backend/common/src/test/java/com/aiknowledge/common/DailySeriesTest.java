package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailySeriesTest {
    @Test
    void fillsEveryDayInTheWindowOldestFirst() {
        LocalDate today = LocalDate.of(2026, 3, 5);
        List<Map<String, Object>> series = DailySeries.fill(today, 3, Map.of(today.toString(), 4L, "2026-03-03", 1L));

        assertEquals(3, series.size());
        assertEquals(List.of("2026-03-03", "2026-03-04", "2026-03-05"), series.stream().map(day -> day.get("date")).toList());
        assertEquals(List.of(1L, 0L, 4L), series.stream().map(day -> day.get("count")).toList());
    }

    @Test
    void ignoresDatesOutsideTheWindowAndEmptyRanges() {
        LocalDate today = LocalDate.of(2026, 3, 5);
        assertEquals(List.of(), DailySeries.fill(today, 0, Map.of(today.toString(), 9L)));
        assertEquals(
                List.of(0L),
                DailySeries.fill(today, 1, Map.of("2026-01-01", 9L)).stream().map(day -> day.get("count")).toList());
    }
}
