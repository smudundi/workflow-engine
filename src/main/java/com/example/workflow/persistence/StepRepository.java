package com.example.workflow.persistence;

import com.example.workflow.domain.RetryPolicy;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StepRepository {

    private final JdbcClient jdbc;

    public StepRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
}
