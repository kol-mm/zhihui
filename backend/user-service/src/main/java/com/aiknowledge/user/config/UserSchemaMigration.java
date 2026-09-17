package com.aiknowledge.user.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies the user_db parts of the docs/sql migrations (v10, v11, v13, v14) on startup, because deploys do not
 * run SQL migrations. Every step is idempotent; adding a secondary index does not block reads or writes in InnoDB.
 */
@Component
@Profile("mysql")
public class UserSchemaMigration implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(UserSchemaMigration.class);

    private final DataSource dataSource;

    public UserSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // The admin user table pages by id within a status or role filter.
            ensureIndex(connection, statement, "user", "idx_user_status_id", "status, id");
            ensureIndex(connection, statement, "user", "idx_user_role_id", "role, id");
            // The moderation report queue pages by id within a status.
            ensureIndex(connection, statement, "user_report", "idx_user_report_status_id", "status, id");
            // Admin-issued password reset codes (v14).
            statement.executeUpdate(PASSWORD_RESET_TABLE);
        }
    }

    static final String PASSWORD_RESET_TABLE = "CREATE TABLE IF NOT EXISTS password_reset_request ("
            + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
            + "user_id BIGINT NOT NULL, "
            + "username VARCHAR(64) NOT NULL, "
            + "contact VARCHAR(100), "
            + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
            + "code_hash VARCHAR(100), "
            + "code_expires_at DATETIME, "
            + "failed_attempts INT NOT NULL DEFAULT 0, "
            + "handled_by BIGINT, "
            + "note VARCHAR(255), "
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
            + "KEY idx_reset_status_id (status, id), "
            + "KEY idx_reset_user_status (user_id, status)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private void ensureIndex(Connection connection, Statement statement, String table, String index, String columns) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            query.setString(1, table);
            query.setString(2, index);
            try (ResultSet result = query.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) return;
            }
        }
        statement.executeUpdate("ALTER TABLE `" + table + "` ADD KEY `" + index + "` (" + columns + ")");
        log.info("Added {} index on {}({})", index, table, columns);
    }
}
