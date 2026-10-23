package com.example.workflow.persistence;

import com.example.workflow.domain.RetryPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StepRepository {

    private static final String STEP_COLUMNS = """
            id, step_key, type, status, attempt, max_attempts, output, last_error,
            next_run_at, started_at, completed_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public StepRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Steps of a workflow in key order. */
    public List<StepRow> findByWorkflow(UUID workflowId) {
        return jdbc.sql("SELECT " + STEP_COLUMNS + " FROM step WHERE workflow_id = :workflowId ORDER BY step_key")
                .param("workflowId", workflowId)
                .query((rs, n) -> mapStep(rs))
                .list();
    }

    public Optional<StepRow> findByWorkflowAndKey(UUID workflowId, String stepKey) {
        return jdbc.sql("SELECT " + STEP_COLUMNS + " FROM step WHERE workflow_id = :workflowId AND step_key = :stepKey")
                .param("workflowId", workflowId)
                .param("stepKey", stepKey)
                .query((rs, n) -> mapStep(rs))
                .optional();
    }

    public void insertStep(UUID workflowId, String stepKey, String type, String inputJson,
                           int remainingDeps, RetryPolicy retry) {
        jdbc.sql("""
                INSERT INTO step (workflow_id, step_key, type, input, status, remaining_deps,
                                  max_attempts, backoff_initial_ms, backoff_multiplier, backoff_max_ms)
                VALUES (:workflowId, :stepKey, :type, CAST(:input AS jsonb), 'PENDING', :remainingDeps,
                        :maxAttempts, :initialDelayMs, :multiplier, :maxDelayMs)
                """)
                .param("workflowId", workflowId)
                .param("stepKey", stepKey)
                .param("type", type)
                .param("input", inputJson)
                .param("remainingDeps", remainingDeps)
                .param("maxAttempts", retry.maxAttempts())
                .param("initialDelayMs", retry.initialDelayMs())
                .param("multiplier", retry.multiplier())
                .param("maxDelayMs", retry.maxDelayMs())
                .update();
    }

    public void insertDependency(UUID workflowId, String stepKey, String dependsOnKey) {
        jdbc.sql("""
                INSERT INTO step_dependency (workflow_id, step_key, depends_on_key)
                VALUES (:workflowId, :stepKey, :dependsOnKey)
                """)
                .param("workflowId", workflowId)
                .param("stepKey", stepKey)
                .param("dependsOnKey", dependsOnKey)
                .update();
    }

    /** Locks the FAILED steps of a workflow, to be taken before the workflow row. */
    public void lockFailed(UUID workflowId) {
        jdbc.sql("SELECT id FROM step WHERE workflow_id = :workflowId AND status = 'FAILED' "
                        + "ORDER BY step_key FOR UPDATE")
                .param("workflowId", workflowId)
                .query(UUID.class)
                .list();
    }

    /** Requeues FAILED steps from a clean attempt count; attempt history is kept. */
    public void resetFailed(UUID workflowId) {
        jdbc.sql("""
                -- attempt restarts per retry round; step_attempt rows remain as history
                UPDATE step SET status = 'PENDING', attempt = 0, next_run_at = now(), last_error = NULL,
                       lease_owner = NULL, lease_expires_at = NULL, completed_at = NULL, updated_at = now()
                WHERE workflow_id = :workflowId AND status = 'FAILED'
                """)
                .param("workflowId", workflowId)
                .update();
    }

    private StepRow mapStep(ResultSet rs) throws SQLException {
        return new StepRow(
                rs.getObject("id", UUID.class),
                rs.getString("step_key"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                readJson(rs.getString("output")),
                rs.getString("last_error"),
                Columns.instant(rs, "next_run_at"),
                Columns.instant(rs, "started_at"),
                Columns.instant(rs, "completed_at"));
    }

    private JsonNode readJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored step output is not valid JSON", e);
        }
    }
}
