package com.aiknowledge.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Writes stored timestamps with their offset ("2026-09-17T07:43:00Z") instead of as bare wall-clock times, which
 * browsers would read as their own local time. Requests may send either form; times with an offset are converted
 * to the storage zone. Registered for every service through Spring Boot auto-configuration.
 */
public class StoredTimeJsonModule extends SimpleModule {
    public StoredTimeJsonModule(ZoneId storageZone) {
        super("StoredTimeJsonModule");
        addSerializer(LocalDateTime.class, new Writer(storageZone));
        addDeserializer(LocalDateTime.class, new Reader(storageZone));
    }

    /** Spring Boot installs every Module bean after the standard Java time module, so these take precedence. */
    @AutoConfiguration(before = JacksonAutoConfiguration.class)
    public static class Registration {
        @Bean
        public StoredTimeJsonModule storedTimeJsonModule() {
            return new StoredTimeJsonModule(AppTime.storageZone());
        }
    }

    static final class Writer extends StdScalarSerializer<LocalDateTime> {
        private final ZoneId storageZone;

        Writer(ZoneId storageZone) {
            super(LocalDateTime.class);
            this.storageZone = storageZone;
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.atZone(storageZone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }

    static final class Reader extends StdScalarDeserializer<LocalDateTime> {
        private final ZoneId storageZone;

        Reader(ZoneId storageZone) {
            super(LocalDateTime.class);
            this.storageZone = storageZone;
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) return null;
            String value = text.trim();
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(storageZone).toLocalDateTime();
            } catch (DateTimeParseException withoutOffset) {
                try {
                    return LocalDateTime.parse(value.replace(' ', 'T'));
                } catch (DateTimeParseException invalid) {
                    return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, value, "expected an ISO date-time");
                }
            }
        }
    }
}
