package com.example.workflow.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StepClaimer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public StepClaimer(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Claims up to {@code limit} ready steps in one transaction. Rows locked by other
     * workers are skipped rather than waited on.
     */
    @Transactional
    public List<ClaimedStep> claim(String workerId, int limit, long leaseMs) {
        if (limit <= 0) {
            return List.of();
        }
        // Lease is set but not renewed yet; heartbeats arrive with US2.2.
        List<ClaimRow> rows = jdbc.sql("""
                UPDATE step SET status = 'RUNNING', attempt = attempt + 1, lease_owner = :worker,
                       lease_expires_at = now() + CAST(:lease AS interval),
                       started_at = COALESCE(started_at, clock_timestamp()), updated_at = now()
                WHERE id IN (
                  SELECT s.id FROM step s JOIN workflow w ON w.id = s.workflow_id
                  WHERE s.status IN ('PENDING', 'RETRYING') AND s.remaining_deps = 0
                    AND s.next_run_at <= now() AND w.status IN ('PENDING', 'RUNNING')
                  ORDER BY s.next_run_at
                  LIMIT :limit
                  -- Locks step rows only; the workflow row is read without a lock here.
                  FOR UPDATE OF s SKIP LOCKED)
                RETURNING id, workflow_id, step_key, type, input::text AS input, attempt, max_attempts
                """)
                .param("worker", workerId)
                .param("lease", leaseMs + " milliseconds")
                .param("limit", limit)
                .query((rs, n) -> new ClaimRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workflow_id", UUID.class),
                        rs.getString("step_key"),
                        rs.getString("type"),
                        rs.getString("input"),
                        rs.getInt("attempt"),
                        rs.getInt("max_attempts")))
                .list();

        return rows.stream().map(row -> toClaimed(row, workerId)).toList();
    }

    private ClaimedStep toClaimed(ClaimRow row, String workerId) {
        // The first claim of a workflow moves it from PENDING to RUNNING.
        jdbc.sql("UPDATE workflow SET status = 'RUNNING', started_at = COALESCE(started_at, now()), "
                + "updated_at = now() WHERE id = :id AND status = 'PENDING'")
                .param("id", row.workflowId())
                .update();
        jdbc.sql("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status) "
                + "VALUES (:id, :attempt, :worker, 'RUNNING')")
                .param("id", row.id())
                .param("attempt", row.attempt())
                .param("worker", workerId)
                .update();
        return new ClaimedStep(row.id(), row.workflowId(), row.stepKey(), row.type(),
                readMap(row.inputJson()), upstreamOutputs(row.workflowId(), row.stepKey()),
                row.attempt(), row.maxAttempts());
    }

    private Map<String, Object> upstreamOutputs(UUID workflowId, String stepKey) {
        List<UpstreamRow> upstream = jdbc.sql("""
                SELECT u.step_key, u.output::text AS output
                FROM step_dependency d
                JOIN step u ON u.workflow_id = d.workflow_id AND u.step_key = d.depends_on_key
                WHERE d.workflow_id = :workflowId AND d.step_key = :stepKey
                ORDER BY u.step_key
                """)
                .param("workflowId", workflowId)
                .param("stepKey", stepKey)
                .query((rs, n) -> new UpstreamRow(rs.getString("step_key"), rs.getString("output")))
                .list();
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (UpstreamRow row : upstream) {
            outputs.put(row.stepKey(), readMap(row.outputJson()));
        }
        return outputs;
    }

    private Map<String, Object> readMap(String text) {
        if (text == null) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Stored JSON is not valid", e);
        }
        return json.convertValue(node, MAP_TYPE);
    }

    private record ClaimRow(UUID id, UUID workflowId, String stepKey, String type,
                            String inputJson, int attempt, int maxAttempts) {
    }

    private record UpstreamRow(String stepKey, String outputJson) {
    }
}
