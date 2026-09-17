package com.aiknowledge.knowledge.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A throwaway MySQL database for migration tests, configured like the service (application-mysql.yml). Tests run
 * only when MIGRATION_TEST_JDBC_URL names a server, e.g. {@code jdbc:mysql://mysql:3306/}; the user needs rights
 * to create and drop databases.
 */
final class MigrationDatabase implements AutoCloseable {
    static final String ENABLED_BY = "MIGRATION_TEST_JDBC_URL";
    private static final String SERVER = System.getenv(ENABLED_BY);
    private static final String USER = System.getenv().getOrDefault("MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=utf8";

    final String name;

    MigrationDatabase(String name) throws SQLException {
        this.name = name;
        try (Connection connection = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
            statement.execute("CREATE DATABASE `" + name + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    Flyway flyway(JavaMigration... javaMigrations) {
        return Flyway.configure()
                .dataSource(SERVER + name + OPTIONS, USER, PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .javaMigrations(javaMigrations)
                .load();
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(SERVER + name + OPTIONS, USER, PASSWORD);
    }

    void execute(String... statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    long number(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    String text(String sql) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    boolean hasIndex(String table, String index) throws SQLException {
        return number("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '"
                + table + "' AND INDEX_NAME = '" + index + "'") > 0;
    }

    boolean hasColumn(String table, String column) throws SQLException {
        return number("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '"
                + table + "' AND COLUMN_NAME = '" + column + "'") > 0;
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }
}
