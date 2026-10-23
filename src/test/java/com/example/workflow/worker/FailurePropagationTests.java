package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
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
class FailurePropagationTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // A single attempt: the first failure exhausts the step.
    private static final RetryPolicy NO_RETRY = new RetryPolicy(1, 0, 1.0, 0);

    @Autowired
    WorkflowService service;

    @Autowired
    StepClaimer claimer;

    @Autowired
    StepCompletion completion;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @Test
    void exhaustedStepFailsWorkflowWithStepError() {
        UUID id = service.submit(chainAtoB(NO_RETRY)).id();

        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "boom");

        assertThat(workflowStatus(id)).isEqualTo("FAILED");
        assertThat(workflowError(id)).isEqualTo("step 'A' failed: boom");
        assertThat(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM workflow WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void dependentNeverStartsAfterUpstreamFails() {
        UUID id = service.submit(chainAtoB(NO_RETRY)).id();
        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "boom");

        assertThat(stepStatus(id, "B")).isEqualTo("PENDING");
        assertThat(claimer.claim("w1", 10, 30_000)).isEmpty();
    }

    @Test
    void independentSiblingCompletingAfterFailureKeepsWorkflowFailed() {
        UUID id = service.submit(withIndependentC(NO_RETRY)).id();
        List<ClaimedStep> ready = claimer.claim("w1", 10, 30_000);
        assertThat(ready).hasSize(2);

        ClaimedStep a = ready.stream().filter(s -> s.stepKey().equals("A")).findFirst().orElseThrow();
        ClaimedStep c = ready.stream().filter(s -> s.stepKey().equals("C")).findFirst().orElseThrow();
        completion.fail(a, "w1", "boom");
        assertThat(completion.complete(c, "w1", Map.of("message", "done"))).isTrue();

        assertThat(workflowStatus(id)).isEqualTo("FAILED");
        assertThat(workflowError(id)).isEqualTo("step 'A' failed: boom");
        assertThat(stepStatus(id, "B")).isEqualTo("PENDING");
    }

    @Test
    void unrelatedReadyStepIsNotClaimedAfterWorkflowFails() {
        UUID id = service.submit(withIndependentC(NO_RETRY)).id();
        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "boom");
        assertThat(workflowStatus(id)).isEqualTo("FAILED");

        assertThat(claimer.claim("w1", 10, 30_000)).isEmpty();
    }

    @Test
    void failureBeforeExhaustionDoesNotFailWorkflow() {
        // Zero delays make the retry claimable immediately.
        UUID id = service.submit(chainAtoB(new RetryPolicy(2, 0, 1.0, 0))).id();

        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "transient");

        assertThat(workflowStatus(id)).isEqualTo("RUNNING");
        assertThat(stepStatus(id, "A")).isEqualTo("RETRYING");
        assertThat(workflowError(id)).isNull();
    }

    @Test
    void lastAllowedAttemptFailureFailsWorkflow() {
        UUID id = service.submit(chainAtoB(new RetryPolicy(2, 0, 1.0, 0))).id();
        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "transient");

        ClaimedStep second = claimer.claim("w1", 1, 30_000).get(0);
        assertThat(second.attempt()).isEqualTo(2);
        completion.fail(second, "w1", "still broken");

        assertThat(stepStatus(id, "A")).isEqualTo("FAILED");
        assertThat(workflowStatus(id)).isEqualTo("FAILED");
        assertThat(workflowError(id)).isEqualTo("step 'A' failed: still broken");
    }

    private String workflowStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM workflow WHERE id = ?", String.class, id);
    }

    private String workflowError(UUID id) {
        return jdbc.queryForObject("SELECT error FROM workflow WHERE id = ?", String.class, id);
    }

    private String stepStatus(UUID workflowId, String stepKey) {
        return jdbc.queryForObject(
                "SELECT status FROM step WHERE workflow_id = ? AND step_key = ?", String.class, workflowId, stepKey);
    }

    // A -> B
    private static WorkflowDefinition chainAtoB(RetryPolicy retry) {
        return new WorkflowDefinition("chain", List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry),
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of("A"), retry)));
    }

    // A -> B, plus an independent C
    private static WorkflowDefinition withIndependentC(RetryPolicy retry) {
        return new WorkflowDefinition("with-c", List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry),
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of("A"), retry),
                new StepSpec("C", "LOG", Map.of("message", "c"), List.of(), retry)));
    }
}
