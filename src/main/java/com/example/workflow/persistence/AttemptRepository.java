package com.example.workflow.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AttemptRepository {

    private final JdbcClient jdbc;

    public AttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All attempts of a workflow, oldest first, across all its steps. */
    public List<AttemptRow> findByWorkflow(UUID workflowId) {
        return jdbc.sql("""
                SELECT a.step_id, a.attempt_no, a.worker_id, a.status, a.error, a.started_at, a.finished_at
                FROM step_attempt a
                JOIN step s ON s.id = a.step_id
                WHERE s.workflow_id = :workflowId
                ORDER BY a.id
                """)
                .param("workflowId", workflowId)
                .query((rs, n) -> new AttemptRow(
                        rs.getObject("step_id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getString("worker_id"),
                        rs.getString("status"),
                        rs.getString("error"),
                        Columns.instant(rs, "started_at"),
                        Columns.instant(rs, "finished_at")))
                .list();
    }
}
