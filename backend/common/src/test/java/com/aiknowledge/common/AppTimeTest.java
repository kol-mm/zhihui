package com.aiknowledge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppTimeTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void daysFollowTheBusinessZone() {
        // 23:30 UTC on the 16th is 07:30 on the 17th in Beijing.
        assertEquals(LocalDate.of(2026, 9, 17), AppTime.businessDate(LocalDateTime.of(2026, 9, 16, 23, 30), SHANGHAI, ZoneOffset.UTC));
        assertEquals(LocalDate.of(2026, 9, 16), AppTime.businessDate(LocalDateTime.of(2026, 9, 16, 15, 59), SHANGHAI, ZoneOffset.UTC));
        // A Beijing day starts at 16:00 UTC the evening before.
        assertEquals(LocalDateTime.of(2026, 9, 16, 16, 0), AppTime.startOfDay(LocalDate.of(2026, 9, 17), SHANGHAI, ZoneOffset.UTC));
        // With both clocks in one zone nothing moves.
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), AppTime.startOfDay(LocalDate.of(2026, 9, 17), SHANGHAI, SHANGHAI));
    }

    @Test
    void sqlShiftsStoredTimesIntoTheBusinessDay() {
        Instant now = Instant.parse("2026-09-17T08:00:00Z");
        assertEquals("DATE(DATE_ADD(created_at, INTERVAL 480 MINUTE))", AppTime.sqlBusinessDate("created_at", SHANGHAI, ZoneOffset.UTC, now));
        assertEquals("DATE(created_at)", AppTime.sqlBusinessDate("created_at", SHANGHAI, SHANGHAI, now));
        assertEquals("DATE(DATE_ADD(t.created_at, INTERVAL -300 MINUTE))",
                AppTime.sqlBusinessDate("t.created_at", ZoneId.of("America/Chicago"), ZoneOffset.UTC, now));
        assertThrows(IllegalArgumentException.class, () -> AppTime.sqlBusinessDate("created_at); DROP TABLE x; --", SHANGHAI, ZoneOffset.UTC, now));
    }

    @Test
    void anUnknownZoneFallsBackToTheDefault() {
        assertEquals(SHANGHAI, AppTime.zoneOrDefault("Mars/Olympus", SHANGHAI));
        assertEquals(SHANGHAI, AppTime.zoneOrDefault(" ", SHANGHAI));
        assertEquals(ZoneId.of("Europe/Berlin"), AppTime.zoneOrDefault("Europe/Berlin", SHANGHAI));
    }

    @Test
    void theApiWritesStoredTimesWithTheirOffsetAndReadsBothForms() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, StoredTimeJsonModule.Registration.class))
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    ZoneId storage = AppTime.storageZone();
                    LocalDateTime stored = LocalDateTime.of(2026, 9, 17, 7, 43, 5);
                    String expected = stored.atZone(storage).toOffsetDateTime().toString();
                    assertThat(mapper.writeValueAsString(Map.of("createdAt", stored))).isEqualTo("{\"createdAt\":\"" + expected + "\"}");

                    Instant instant = Instant.parse("2026-09-17T07:43:05Z");
                    assertEquals(LocalDateTime.ofInstant(instant, storage), mapper.readValue("\"2026-09-17T15:43:05+08:00\"", LocalDateTime.class));
                    assertEquals(stored, mapper.readValue("\"2026-09-17T07:43:05\"", LocalDateTime.class));
                    assertEquals(stored, mapper.readValue("\"2026-09-17 07:43:05\"", LocalDateTime.class));
                });
    }

    @Test
    void utcTimesEndInZ() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new StoredTimeJsonModule(ZoneOffset.UTC));
        assertEquals("\"2026-09-17T07:43:00Z\"", mapper.writeValueAsString(LocalDateTime.of(2026, 9, 17, 7, 43)));
        assertEquals("\"2026-09-17T07:43:00.25+08:00\"",
                new ObjectMapper().registerModule(new StoredTimeJsonModule(SHANGHAI)).writeValueAsString(LocalDateTime.of(2026, 9, 17, 7, 43, 0, 250_000_000)));
    }
}
