package com.aiknowledge.message.controller;

import com.aiknowledge.common.DatabaseReadiness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

/** Container health check target: 503 while the database is unreachable (see DatabaseReadiness). */
@RestController
public class ReadinessController {
    private final ObjectProvider<DataSource> dataSource;

    public ReadinessController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/message/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        return DatabaseReadiness.probe("message-service", dataSource.getIfAvailable());
    }
}
