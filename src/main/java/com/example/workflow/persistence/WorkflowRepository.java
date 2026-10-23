package com.example.workflow.persistence;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowRepository {

    private final JdbcClient jdbc;

    public WorkflowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WorkflowRow> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, name, status, error, created_at, started_at, completed_at
                FROM workflow
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new WorkflowRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("error"),
                        Columns.instant(rs, "created_at"),
                        Columns.instant(rs, "started_at"),
                        Columns.instant(rs, "completed_at")))
                .optional();
    }

    /** Newest first; a null status matches every workflow. */
    public List<WorkflowRow> findPage(String status, int limit, long offset) {
        return jdbc.sql("""
                SELECT id, name, status, error, created_at, started_at, completed_at
                FROM workflow
                WHERE (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """)
                .param("status", status, Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new WorkflowRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("error"),
                        Columns.instant(rs, "created_at"),
                        Columns.instant(rs, "started_at"),
                        Columns.instant(rs, "completed_at")))
                .list();
    }

    public long count(String status) {
        return jdbc.sql("SELECT count(*) FROM workflow WHERE (CAST(:status AS text) IS NULL OR status = :status)")
                .param("status", status, Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    public UUID insert(String name, String status, String definitionJson) {
        return jdbc.sql("""
                INSERT INTO workflow (name, status, definition)
                VALUES (:name, :status, CAST(:definition AS jsonb))
                RETURNING id
                """)
                .param("name", name)
                .param("status", status)
                .param("definition", definitionJson)
                .query(UUID.class)
                .single();
    }

    /** Locks the workflow row and returns its status. */
    public Optional<String> lockStatus(UUID id) {
        return jdbc.sql("SELECT status FROM workflow WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public void markRunning(UUID id) {
        jdbc.sql("""
                UPDATE workflow SET status = 'RUNNING', error = NULL, completed_at = NULL, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .update();
    }
}
