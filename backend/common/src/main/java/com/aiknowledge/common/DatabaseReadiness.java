package com.aiknowledge.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness probe shared by the MySQL-backed services. Unlike the informational /health endpoints it answers
 * HTTP 503 while the database is unreachable, so container health checks report the real state.
 */
public final class DatabaseReadiness {
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private DatabaseReadiness() {
    }

    public static ResponseEntity<Map<String, Object>> probe(String service, DataSource dataSource) {
        String database = dataSource == null ? "NOT_CONFIGURED" : (isReachable(dataSource) ? "UP" : "DOWN");
        boolean ready = !"DOWN".equals(database);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", service);
        body.put("status", ready ? "UP" : "DOWN");
        body.put("database", database);
        body.put("time", Instant.now().toString());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    static boolean isReachable(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException error) {
            return false;
        }
    }
}
