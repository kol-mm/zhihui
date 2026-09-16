package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeCursorTest {
    @Test
    void roundTripsThroughItsTextForm() {
        LocalDateTime time = LocalDateTime.of(2026, 9, 16, 10, 44, 5);
        String text = TimeCursor.format(time, 123L);
        assertEquals("2026-09-16T10:44:05|123", text);
        assertEquals(new TimeCursor(time, 123L), TimeCursor.parse(text));
        // Whole minutes print without seconds and still parse.
        assertEquals(new TimeCursor(LocalDateTime.of(2026, 9, 16, 10, 44), 7L),
                TimeCursor.parse(TimeCursor.format(LocalDateTime.of(2026, 9, 16, 10, 44), 7L)));
    }

    @Test
    void ordersNewestFirstWithTheIdBreakingTies() {
        TimeCursor cursor = new TimeCursor(LocalDateTime.of(2026, 9, 16, 10, 0), 50L);
        assertTrue(cursor.isAfter(LocalDateTime.of(2026, 9, 16, 9, 59), 99L));
        assertTrue(cursor.isAfter(LocalDateTime.of(2026, 9, 16, 10, 0), 49L));
        assertFalse(cursor.isAfter(LocalDateTime.of(2026, 9, 16, 10, 0), 50L));
        assertFalse(cursor.isAfter(LocalDateTime.of(2026, 9, 16, 10, 1), 1L));
        assertFalse(cursor.isAfter(null, 1L));
    }

    @Test
    void rejectsMalformedCursors() {
        assertNull(TimeCursor.parse(null));
        assertNull(TimeCursor.parse(" "));
        for (String bad : new String[]{"123", "2026-09-16T10:44|", "|5", "yesterday|5", "2026-09-16T10:44|x", "2026-09-16T10:44|-1"}) {
            assertThrows(IllegalArgumentException.class, () -> TimeCursor.parse(bad), bad);
        }
        assertNull(TimeCursor.format(null, 1L));
    }
}
