package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
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
class LeaseReaperTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy RETRY = new RetryPolicy(3, 0, 1.0, 0);
    private static final RetryPolicy ONE_ATTEMPT = new RetryPolicy(1, 0, 1.0, 0);

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
    void expiredLeaseIsRequeuedWithTimedOutAttempt() {
        UUID id = service.submit(single("requeue", RETRY)).id();
        ClaimedStep first = claimer.claim("w1", 1, 30_000).get(0);
        expire(first.id());

        assertThat(reaper.reapExpired()).isEqualTo(1);

        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("RETRYING");
        assertThat(attemptStatus(first.id(), 1)).isEqualTo("TIMED_OUT");
        assertThat(completion.complete(first, "w1", Map.of("message", "late"))).isFalse();

        ClaimedStep second = claimer.claim("w2", 1, 30_000).get(0);
        assertThat(second.attempt()).isEqualTo(2);
    }

    @Test
    void exhaustedExpiredLeaseFailsStepAndWorkflow() {
        UUID id = service.submit(single("exhaust", ONE_ATTEMPT)).id();
        ClaimedStep only = claimer.claim("w1", 1, 30_000).get(0);
        expire(only.id());

        assertThat(reaper.reapExpired()).isEqualTo(1);

        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(attemptStatus(only.id(), 1)).isEqualTo("TIMED_OUT");
        assertThat(jdbc.queryForObject("SELECT status FROM workflow WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM workflow WHERE id = ?", String.class, id))
                .contains("lease expired");
    }

    @Test
    void liveLeaseIsNotReaped() {
        UUID id = service.submit(single("live", RETRY)).id();
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);

        assertThat(reaper.reapExpired()).isZero();
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(completion.complete(a, "w1", Map.of("message", "done"))).isTrue();
    }

    private void expire(UUID stepId) {
        jdbc.update("UPDATE step SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", stepId);
    }

    private String attemptStatus(UUID stepId, int attemptNo) {
        return jdbc.queryForObject(
                "SELECT status FROM step_attempt WHERE step_id = ? AND attempt_no = ?",
                String.class, stepId, attemptNo);
    }

    private static WorkflowDefinition single(String name, RetryPolicy retry) {
        return new WorkflowDefinition(name, List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry)));
    }
}
