package com.aiknowledge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalJsonStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private LocalJsonStore() {
    }

    public static Path dataFile(String fileName) {
        String baseDir = System.getProperty("LOCAL_STORE_DIR", "data/local-store");
        return Path.of(baseDir).resolve(fileName);
    }

    public static synchronized <T> T read(Path path, Class<T> type, T fallback) {
        if (!Files.exists(path)) {
            return fallback;
        }
        try {
            return MAPPER.readValue(path.toFile(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read local store: " + path, ex);
        }
    }

    public static synchronized void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write local store: " + path, ex);
        }
    }
}
