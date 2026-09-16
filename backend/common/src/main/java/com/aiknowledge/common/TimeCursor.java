package com.aiknowledge.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Keyset cursor for lists ordered by a timestamp, newest first, with the id breaking ties. It travels as
 * {@code <ISO local date-time>|<id>}, for example {@code 2026-09-16T10:44:05|123}, and clients pass it back
 * unchanged.
 */
public record TimeCursor(LocalDateTime time, long id) {
    public TimeCursor {
        if (time == null) throw new IllegalArgumentException("cursor time is required");
        if (id < 0) throw new IllegalArgumentException("cursor id must not be negative");
    }

    /** Returns null for a missing cursor and throws {@link IllegalArgumentException} for a malformed one. */
    public static TimeCursor parse(String value) {
        if (value == null || value.isBlank()) return null;
        int separator = value.lastIndexOf('|');
        if (separator <= 0 || separator == value.length() - 1) throw new IllegalArgumentException("invalid cursor");
        try {
            return new TimeCursor(LocalDateTime.parse(value.substring(0, separator)), Long.parseLong(value.substring(separator + 1)));
        } catch (DateTimeParseException | NumberFormatException error) {
            throw new IllegalArgumentException("invalid cursor", error);
        }
    }

    public static String format(LocalDateTime time, Long id) {
        return time == null || id == null ? null : time + "|" + id;
    }

    /** True when a row with this time and id comes after the cursor in newest-first order. */
    public boolean isAfter(LocalDateTime rowTime, Long rowId) {
        if (rowTime == null || rowId == null) return false;
        int compared = rowTime.compareTo(time);
        return compared < 0 || (compared == 0 && rowId < id);
    }
}
