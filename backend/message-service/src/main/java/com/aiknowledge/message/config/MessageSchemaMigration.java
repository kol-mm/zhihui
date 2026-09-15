package com.aiknowledge.message.config;

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
 * Applies the message_db parts of docs/sql/migration-v6-query-indexes.sql on startup, because deploys do not run
 * SQL migrations. Adding a secondary index is idempotent here and does not block reads or writes in InnoDB.
 */
@Component
@Profile("mysql")
public class MessageSchemaMigration implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(MessageSchemaMigration.class);

    private final DataSource dataSource;

    public MessageSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ensureIndex(connection, statement, "chat_message", "idx_message_session_id", "session_id, id");
            ensureIndex(connection, statement, "chat_session", "idx_session_pair", "user_a_id, user_b_id");
            ensureIndex(connection, statement, "chat_session", "idx_session_user_b", "user_b_id");
            ensureIndex(connection, statement, "notification", "idx_notification_user", "user_id, is_read, id");
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
