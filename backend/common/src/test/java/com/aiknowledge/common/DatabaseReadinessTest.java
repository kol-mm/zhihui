package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseReadinessTest {
    @Test
    void readyWithoutDatabaseInLocalMode() {
        var response = DatabaseReadiness.probe("svc", null);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("NOT_CONFIGURED", response.getBody().get("database"));
    }

    @Test
    void readyWhenConnectionIsValid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        var response = DatabaseReadiness.probe("svc", dataSource);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("database"));
    }

    @Test
    void unavailableWhenDatabaseCannotBeReached() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("Communications link failure"));
        var response = DatabaseReadiness.probe("svc", dataSource);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
    }
}
