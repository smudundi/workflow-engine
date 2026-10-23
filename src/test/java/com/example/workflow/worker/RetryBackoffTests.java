package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.StepRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "engine.worker.enabled=false")
@Testcontainers
class RetryBackoffTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // 1 s initial delay with 2x growth keeps the gaps far apart from the ±20% jitter.
    private static final RetryPolicy BACKOFF = new RetryPolicy(3, 1000, 2.0, 10_000);

    @Autowired
    WorkflowService service;

    @Autowired
    StepClaimer claimer;

    @Autowired
    StepCompletion completion;

    @Autowired
    LeaseReaper reaper;

    @Autowired
    StepRepository steps;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @Test
    void flakyStepRetriesWithGrowingDelaysUntilItSucceeds() {
        UUID id = service.submit(single("flaky", BACKOFF)).id();

        ClaimedStep first = claimer.claim("w1", 1, 30_000).get(0);
        completion.fail(first, "w1", "transient 1");
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("RETRYING");
        double firstGap = gapAfterFailure(id, 1);

        ClaimedStep second = awaitClaim();
        completion.fail(second, "w1", "transient 2");
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("RETRYING");
        double secondGap = gapAfterFailure(id, 2);

        ClaimedStep third = awaitClaim();
        assertThat(third.attempt()).isEqualTo(3);
        assertThat(completion.complete(third, "w1", Map.of("message", "ok"))).isTrue();
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("COMPLETED");

        assertThat(firstGap).isBetween(800.0, 1300.0);
        assertThat(secondGap).isBetween(1600.0, 2500.0);
        assertThat(secondGap).isGreaterThan(firstGap);
    }

    @Test
    void retryIsNotClaimableBeforeBackoffExpires() {
        service.submit(single("waiting", BACKOFF));
        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "transient");

        assertThat(claimer.claim("w1", 1, 30_000)).isEmpty();
    }

    @Test
    void reapedStepWaitsForBackoffBeforeRequeue() {
        service.submit(single("reaped", BACKOFF));
        claimer.claim("w1", 1, 30_000);
        jdbc.update("UPDATE step SET lease_expires_at = now() - interval '1 second'");

        assertThat(reaper.reapExpired()).isEqualTo(1);

        assertThat(claimer.claim("w1", 1, 30_000)).isEmpty();
    }

    private ClaimedStep awaitClaim() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<ClaimedStep> claimed = claimer.claim("w1", 1, 30_000);
            if (!claimed.isEmpty()) {
                return claimed.get(0);
            }
            sleep(10);
        }
        throw new AssertionError("step was not re-claimed within 5 s");
    }

    // Time from the end of the failed attempt to the next eligible run, in ms.
    private double gapAfterFailure(UUID workflowId, int attemptNo) {
        Double gap = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (s.next_run_at - a.finished_at)) * 1000
                FROM step s JOIN step_attempt a ON a.step_id = s.id
                WHERE s.workflow_id = ? AND s.step_key = 'A' AND a.attempt_no = ?
                """, Double.class, workflowId, attemptNo);
        return gap;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static WorkflowDefinition single(String name, RetryPolicy retry) {
        return new WorkflowDefinition(name, List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry)));
    }
}
