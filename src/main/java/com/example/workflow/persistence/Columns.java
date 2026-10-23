package com.example.workflow.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Column helpers shared by the row mappers. */
final class Columns {

    private Columns() {
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
