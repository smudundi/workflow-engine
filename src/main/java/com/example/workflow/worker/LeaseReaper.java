package com.example.workflow.worker;

import com.example.workflow.domain.BackoffPolicy;
import com.example.workflow.domain.RetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovers RUNNING steps whose worker stopped renewing its lease. */
@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JdbcClient jdbc;
    private final StepCompletion completion;
    private final TransactionTemplate tx;
    private final Counter reapedSteps;

    public LeaseReaper(JdbcClient jdbc, StepCompletion completion, TransactionTemplate tx, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.completion = completion;
        this.tx = tx;
        this.reapedSteps = Counter.builder("workflow.steps.reaped")
                .description("Steps recovered from expired leases").register(meters);
    }

    // Scheduling is enabled by WorkerConfiguration, so the reaper runs only in worker roles.
    @Scheduled(fixedDelay = 5_000)
    void scheduledReap() {
        try {
            int reaped = reapExpired();
            if (reaped > 0) {
                log.warn("reaped {} step(s) with expired leases", reaped);
            }
        } catch (RuntimeException e) {
            log.error("lease reaper failed: {}", e.getMessage());
        }
    }

    /** Returns the number of steps whose expired lease was reaped. */
    public int reapExpired() {
        return tx.execute(status -> reap());
    }

    private int reap() {
        List<Expired> expired = jdbc.sql("""
                UPDATE step SET status = CASE WHEN attempt >= max_attempts THEN 'FAILED' ELSE 'RETRYING' END,
                       last_error = 'lease expired', lease_owner = NULL, lease_expires_at = NULL,
                       next_run_at = now(),
                       completed_at = CASE WHEN attempt >= max_attempts THEN clock_timestamp() ELSE NULL END,
                       updated_at = now()
                WHERE id IN (
                  SELECT id FROM step
                  WHERE status = 'RUNNING' AND lease_expires_at < now()
                  -- Rows locked by a renewing or completing worker are skipped, not expired.
                  FOR UPDATE SKIP LOCKED)
                RETURNING id, workflow_id, step_key, attempt, status, max_attempts,
                          backoff_initial_ms, backoff_multiplier, backoff_max_ms
                """)
                .query((rs, n) -> new Expired(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workflow_id", UUID.class),
                        rs.getString("step_key"),
                        rs.getInt("attempt"),
                        rs.getString("status"),
                        new RetryPolicy(
                                rs.getInt("max_attempts"),
                                rs.getLong("backoff_initial_ms"),
                                rs.getDouble("backoff_multiplier"),
                                rs.getLong("backoff_max_ms"))))
                .list();

        for (Expired row : expired) {
            jdbc.sql("""
                    UPDATE step_attempt SET status = 'TIMED_OUT', error = 'lease expired', finished_at = clock_timestamp()
                    WHERE step_id = :id AND attempt_no = :attempt AND status = 'RUNNING'
                    """)
                    .param("id", row.id())
                    .param("attempt", row.attempt())
                    .update();
            if ("FAILED".equals(row.status())) {
                completion.failWorkflow(row.workflowId(), "step '" + row.stepKey() + "' failed: lease expired");
            } else {
                long delayMs = BackoffPolicy.delayMs(row.retry(), row.attempt(), ThreadLocalRandom.current().nextDouble());
                jdbc.sql("UPDATE step SET next_run_at = now() + CAST(:delay AS interval) WHERE id = :id")
                        .param("delay", delayMs + " milliseconds")
                        .param("id", row.id())
                        .update();
            }
        }
        reapedSteps.increment(expired.size());
        return expired.size();
    }

    private record Expired(UUID id, UUID workflowId, String stepKey, int attempt, String status, RetryPolicy retry) {
    }
}
