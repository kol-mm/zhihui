package com.aiknowledge.community.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This service has migrations in two places: SQL files under db/migration and a Java migration beside this
 * test. Flyway refuses to start when two of them claim the same version, and that only shows up when the
 * service boots — a listing of the SQL folder alone does not reveal the Java one. This keeps the numbering
 * honest at build time instead.
 */
class MigrationVersionsTest {
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.(sql|java)$");

    private Map<String, List<String>> migrationsByVersion() throws IOException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Path root : List.of(Path.of("src/main/resources/db/migration"), Path.of("src/main/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    Matcher matcher = VERSIONED.matcher(file.getFileName().toString());
                    if (matcher.matches()) {
                        found.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                                .add(file.getFileName().toString());
                    }
                });
            }
        }
        return found;
    }

    @Test
    void noTwoMigrationsClaimTheSameVersion() throws IOException {
        Map<String, List<String>> byVersion = migrationsByVersion();

        List<String> clashes = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V" + entry.getKey() + " is claimed by " + entry.getValue())
                .toList();

        assertEquals(List.of(), clashes, "Flyway will refuse to start with these");
    }

    @Test
    void theSqlAndJavaMigrationsAreBothSeen() throws IOException {
        Map<String, List<String>> byVersion = migrationsByVersion();

        // A guard on the guard: if the scan stopped finding files, the test above would pass for the wrong reason.
        assertTrue(byVersion.containsKey("2") && byVersion.get("2").get(0).endsWith(".java"),
                "the Java migration should be found, got " + byVersion);
        assertTrue(byVersion.containsKey("1") && byVersion.get("1").get(0).endsWith(".sql"),
                "the baseline SQL migration should be found, got " + byVersion);
    }
}
