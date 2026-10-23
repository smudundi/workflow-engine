package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.ConflictException;
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
class WorkflowRetryTests {

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
    void retryRequeuesFailedStepAndCompletesWorkflowWithoutRerunningCompletedSteps() {
        UUID id = service.submit(withIndependentC()).id();
        List<ClaimedStep> ready = claimer.claim("w1", 10, 30_000);
        completion.complete(find(ready, "C"), "w1", Map.of("message", "done"));
        completion.fail(find(ready, "A"), "w1", "transient");
        assertThat(workflowStatus(id)).isEqualTo("FAILED");

        service.retry(id);

        assertThat(workflowStatus(id)).isEqualTo("RUNNING");
        assertThat(workflowError(id)).isNull();
        assertThat(stepStatus(id, "A")).isEqualTo("PENDING");
        assertThat(attemptCount(id, "A")).isEqualTo(1);
        assertThat(stepStatus(id, "C")).isEqualTo("COMPLETED");
        assertThat(attemptCount(id, "C")).isEqualTo(1);

        ClaimedStep retried = claimer.claim("w1", 10, 30_000).stream()
                .filter(s -> s.stepKey().equals("A")).findFirst().orElseThrow();
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(completion.complete(retried, "w1", Map.of("message", "ok"))).isTrue();
        ClaimedStep b = claimer.claim("w1", 10, 30_000).get(0);
        assertThat(b.stepKey()).isEqualTo("B");
        assertThat(completion.complete(b, "w1", Map.of("message", "ok"))).isTrue();

        assertThat(workflowStatus(id)).isEqualTo("COMPLETED");
        assertThat(attemptCount(id, "A")).isEqualTo(2);
        assertThat(attemptCount(id, "C")).isEqualTo(1);
    }

    @Test
    void retryOfNonFailedWorkflowIsRejectedAndChangesNothing() {
        UUID id = service.submit(withIndependentC()).id();

        assertThatThrownBy(() -> service.retry(id)).isInstanceOf(ConflictException.class);
        assertThat(workflowStatus(id)).isEqualTo("PENDING");
        assertThat(stepStatus(id, "A")).isEqualTo("PENDING");
    }

    private ClaimedStep find(List<ClaimedStep> claimed, String key) {
        return claimed.stream().filter(s -> s.stepKey().equals(key)).findFirst().orElseThrow();
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

    private int attemptCount(UUID workflowId, String stepKey) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM step_attempt a JOIN step s ON s.id = a.step_id
                WHERE s.workflow_id = ? AND s.step_key = ?
                """, Integer.class, workflowId, stepKey);
    }

    // A -> B, plus an independent C
    private static WorkflowDefinition withIndependentC() {
        return new WorkflowDefinition("with-c", List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), NO_RETRY),
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of("A"), NO_RETRY),
                new StepSpec("C", "LOG", Map.of("message", "c"), List.of(), NO_RETRY)));
    }
}
