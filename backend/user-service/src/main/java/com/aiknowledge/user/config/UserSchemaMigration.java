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
 * Applies the user_db parts of docs/sql/migration-v10-admin-table-indexes.sql on startup, because deploys do not
 * run SQL migrations. Adding a secondary index is idempotent here and does not block reads or writes in InnoDB.
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
        }
    }

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
