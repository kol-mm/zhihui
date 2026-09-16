package com.aiknowledge.community.config;

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
 * Applies the community_db parts of docs/sql/migration-v5-comment-threads.sql, migration-v6-query-indexes.sql
 * and migration-v7-comment-is-root.sql
 * on startup, because deploys do not run SQL migrations. Every step is idempotent and runs while the context is
 * refreshing, before the web server accepts requests.
 */
@Component
@Profile("mysql")
public class CommunitySchemaMigration implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(CommunitySchemaMigration.class);
    private static final int MAX_BACKFILL_PASSES = 64;

    private final DataSource dataSource;

    public CommunitySchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            if (count(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND COLUMN_NAME = 'root_id'") == 0) {
                statement.executeUpdate("ALTER TABLE comment ADD COLUMN root_id BIGINT NULL AFTER parent_id");
                log.info("Added comment.root_id column");
            }
            if (count(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND COLUMN_NAME = 'is_root'") == 0) {
                statement.executeUpdate("ALTER TABLE comment ADD COLUMN is_root TINYINT(1) NOT NULL DEFAULT 0 AFTER root_id");
                int roots = statement.executeUpdate("UPDATE comment SET is_root = 1 WHERE root_id = id");
                log.info("Added comment.is_root column and marked {} thread roots", roots);
            }
            ensureIndex(connection, statement, "comment", "idx_comment_thread", "post_id, root_id, id");
            ensureIndex(connection, statement, "comment", "idx_comment_root", "post_id, is_root, id");
            ensureIndex(connection, statement, "post_like", "idx_post_like_post", "post_id");
            ensureIndex(connection, statement, "post_collect", "idx_post_collect_post", "post_id");
            ensureIndex(connection, statement, "post_image", "idx_post_image_post", "post_id, sort_no");
            // The moderation queues page posts by id within a status.
            ensureIndex(connection, statement, "post", "idx_post_status_id", "status, id");
            // Analytics reads published posts created inside a time window.
            ensureIndex(connection, statement, "post", "idx_post_status_created", "status, created_at");
            backfillCommentRootIds(connection, statement);
        }
    }

    private void backfillCommentRootIds(Connection connection, Statement statement) throws SQLException {
        if (count(connection, "SELECT COUNT(*) FROM comment WHERE root_id IS NULL") == 0) return;
        int updated = statement.executeUpdate("UPDATE comment SET root_id = id, is_root = 1 WHERE root_id IS NULL AND (parent_id IS NULL OR parent_id <= 0)");
        for (int pass = 0; pass < MAX_BACKFILL_PASSES; pass++) {
            int replies = statement.executeUpdate("UPDATE comment child JOIN comment parent ON child.parent_id = parent.id "
                    + "SET child.root_id = parent.root_id WHERE child.root_id IS NULL AND parent.root_id IS NOT NULL");
            updated += replies;
            if (replies == 0) break;
        }
        // Replies whose parent no longer exists (or parent cycles) become their own thread root.
        updated += statement.executeUpdate("UPDATE comment SET root_id = id, is_root = 1 WHERE root_id IS NULL");
        log.info("Backfilled comment.root_id for {} rows", updated);
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

    private long count(Connection connection, String countSql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(countSql); ResultSet result = query.executeQuery()) {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
