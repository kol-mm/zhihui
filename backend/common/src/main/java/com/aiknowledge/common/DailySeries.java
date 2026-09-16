package com.aiknowledge.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the daily series the admin analytics charts draw. Stores only report the days that
 * actually have rows, so every missing day in the window is filled with a zero here and the
 * chart always receives the same number of bars.
 */
public final class DailySeries {
    private DailySeries() {
    }

    /**
     * @param endDay the newest day in the series, usually today
     * @param days   how many days the series covers, ending at {@code endDay}
     * @param counts counts keyed by ISO date; days without rows may be missing
     * @return one entry per day, oldest first, each holding {@code date} and {@code count}
     */
    public static List<Map<String, Object>> fill(LocalDate endDay, int days, Map<String, Long> counts) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (int offset = Math.max(days, 0) - 1; offset >= 0; offset--) {
            String date = endDay.minusDays(offset).toString();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date);
            day.put("count", counts.getOrDefault(date, 0L));
            series.add(day);
        }
        return series;
    }
}
