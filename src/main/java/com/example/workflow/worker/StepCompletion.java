package com.example.workflow.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the outcome of a claimed step. Updates are fenced on lease owner, attempt and
 * RUNNING status, so a stale worker's result is discarded instead of overwriting newer state.
 */
@Component
public class StepCompletion {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public StepCompletion(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Returns false when the lease was lost and the result was discarded. */
    @Transactional
    public boolean complete(ClaimedStep step, String workerId, Map<String, Object> output) {
        // Fenced: applies only if this worker still holds the current attempt.
        // clock_timestamp(): now() is the transaction start, which can precede the commit.
        int updated = jdbc.sql("""
                UPDATE step SET status = 'COMPLETED', output = CAST(:output AS jsonb), last_error = NULL,
                       lease_owner = NULL, lease_expires_at = NULL, completed_at = clock_timestamp(), updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempt = :attempt AND lease_owner = :worker
                """)
                .param("output", toJson(output))
                .param("id", step.id())
                .param("attempt", step.attempt())
                .param("worker", workerId)
                .update();
        if (updated == 0) {
            return false;
        }
        finishAttempt(step, "SUCCEEDED", null);
        // A dependent becomes claimable once its remaining_deps counter reaches 0.
        jdbc.sql("""
                UPDATE step SET remaining_deps = remaining_deps - 1, updated_at = now()
                WHERE workflow_id = :workflowId
                  AND step_key IN (SELECT d.step_key FROM step_dependency d
                                   WHERE d.workflow_id = :workflowId AND d.depends_on_key = :stepKey)
                """)
                .param("workflowId", step.workflowId())
                .param("stepKey", step.stepKey())
                .update();
        finalizeWorkflowIfDone(step.workflowId());
        return true;
    }

    /**
     * Records a failed attempt. The step goes to RETRYING while attempts remain and to
     * FAILED once they are exhausted, which also fails the workflow.
     */
    @Transactional
    public boolean fail(ClaimedStep step, String workerId, String error) {
        // Retries are immediate until US2.4 adds backoff.
        boolean exhausted = step.attempt() >= step.maxAttempts();
        String status = exhausted ? "FAILED" : "RETRYING";
        int updated = jdbc.sql("""
                UPDATE step SET status = :status, last_error = :error, lease_owner = NULL,
                       lease_expires_at = NULL, next_run_at = now(),
                       completed_at = CASE WHEN :status = 'FAILED' THEN clock_timestamp() ELSE NULL END,
                       updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempt = :attempt AND lease_owner = :worker
                """)
                .param("status", status)
                .param("error", error)
                .param("id", step.id())
                .param("attempt", step.attempt())
                .param("worker", workerId)
                .update();
        if (updated == 0) {
            return false;
        }
        finishAttempt(step, "FAILED", error);
        if (exhausted) {
            failWorkflow(step.workflowId(), "step '" + step.stepKey() + "' failed: " + error);
        }
        return true;
    }

    private void finishAttempt(ClaimedStep step, String status, String error) {
        jdbc.sql("""
                UPDATE step_attempt SET status = :status, error = :error, finished_at = clock_timestamp()
                WHERE step_id = :id AND attempt_no = :attempt
                """)
                .param("status", status)
                .param("error", error)
                .param("id", step.id())
                .param("attempt", step.attempt())
                .update();
    }

    /** Marks the workflow COMPLETED once every step is done. Row lock serializes parallel finishers. */
    private void finalizeWorkflowIfDone(UUID workflowId) {
        String status = lockWorkflowStatus(workflowId);
        if (!"RUNNING".equals(status)) {
            return;
        }
        Long unfinished = jdbc.sql("SELECT count(*) FROM step WHERE workflow_id = :id AND status <> 'COMPLETED'")
                .param("id", workflowId)
                .query(Long.class)
                .single();
        if (unfinished == 0) {
            jdbc.sql("UPDATE workflow SET status = 'COMPLETED', completed_at = now(), updated_at = now() "
                    + "WHERE id = :id")
                    .param("id", workflowId)
                    .update();
        }
    }

    void failWorkflow(UUID workflowId, String error) {
        String status = lockWorkflowStatus(workflowId);
        if ("PENDING".equals(status) || "RUNNING".equals(status)) {
            jdbc.sql("UPDATE workflow SET status = 'FAILED', error = :error, completed_at = now(), "
                    + "updated_at = now() WHERE id = :id")
                    .param("error", error)
                    .param("id", workflowId)
                    .update();
        }
    }

    // Takes the workflow row lock; always acquired after the step lock to avoid deadlocks.
    private String lockWorkflowStatus(UUID workflowId) {
        return jdbc.sql("SELECT status FROM workflow WHERE id = :id FOR UPDATE")
                .param("id", workflowId)
                .query(String.class)
                .single();
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize step output", e);
        }
    }
}
