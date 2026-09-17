package com.aiknowledge.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The two clocks the platform works with.
 *
 * <p>Timestamps are stored as wall-clock times without a zone ({@code DATETIME}, {@link LocalDateTime}) in the
 * storage zone: the zone of the JVM, which must match the MySQL server's (both UTC in the Docker stack). The API
 * writes them with their offset (see {@link StoredTimeJsonModule}), so browsers show local time.
 *
 * <p>Calendar days — "today", the bars of the analytics trend — follow the business zone, {@code APP_TIME_ZONE}
 * (default Asia/Shanghai), so a post at 07:00 in Beijing counts for that Beijing day.
 */
public final class AppTime {
    private static final ZoneId BUSINESS_ZONE = zoneOrDefault(System.getenv("APP_TIME_ZONE"), ZoneId.of("Asia/Shanghai"));

    private AppTime() {
    }

    public static ZoneId storageZone() {
        return ZoneId.systemDefault();
    }

    public static ZoneId businessZone() {
        return BUSINESS_ZONE;
    }

    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    /** The stored wall-clock time at which {@code day} begins in the business zone. */
    public static LocalDateTime startOfDay(LocalDate day) {
        return startOfDay(day, BUSINESS_ZONE, storageZone());
    }

    static LocalDateTime startOfDay(LocalDate day, ZoneId business, ZoneId storage) {
        return day.atStartOfDay(business).withZoneSameInstant(storage).toLocalDateTime();
    }

    /** The business day a stored timestamp falls on. */
    public static LocalDate businessDate(LocalDateTime stored) {
        return businessDate(stored, BUSINESS_ZONE, storageZone());
    }

    static LocalDate businessDate(LocalDateTime stored, ZoneId business, ZoneId storage) {
        return stored.atZone(storage).withZoneSameInstant(business).toLocalDate();
    }

    /**
     * SQL for the business day of a stored column, e.g. {@code DATE(DATE_ADD(created_at, INTERVAL 480 MINUTE))}.
     * The offset is today's, which is exact for zones without daylight saving time (such as China's).
     */
    public static String sqlBusinessDate(String column) {
        return sqlBusinessDate(column, BUSINESS_ZONE, storageZone(), Instant.now());
    }

    static String sqlBusinessDate(String column, ZoneId business, ZoneId storage, Instant at) {
        if (!column.matches("[A-Za-z_][A-Za-z0-9_.]*")) throw new IllegalArgumentException("not a column name: " + column);
        int minutes = (business.getRules().getOffset(at).getTotalSeconds() - storage.getRules().getOffset(at).getTotalSeconds()) / 60;
        return minutes == 0 ? "DATE(" + column + ")" : "DATE(DATE_ADD(" + column + ", INTERVAL " + minutes + " MINUTE))";
    }

    static ZoneId zoneOrDefault(String value, ZoneId fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return ZoneId.of(value.trim());
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }
}
