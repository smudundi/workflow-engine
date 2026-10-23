package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRepository;
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
class StepCompletionTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy ONE_ATTEMPT = new RetryPolicy(1, 0, 1.0, 0);
    private static final RetryPolicy TWO_ATTEMPTS = new RetryPolicy(2, 0, 1.0, 0);

    @Autowired
    WorkflowService service;

    @Autowired
    StepClaimer claimer;

    @Autowired
    StepCompletion completion;

    @Autowired
    StepRepository steps;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @Test
    void completingLastStepCompletesWorkflowOnce() {
        UUID id = service.submit(chain("done", ONE_ATTEMPT)).id();

        completion.complete(claimer.claim("w1", 1, 30_000).get(0), "w1", Map.of("message", "a"));
        completion.complete(claimer.claim("w1", 1, 30_000).get(0), "w1", Map.of("message", "b"));

        assertThat(workflows.findById(id).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(workflows.findById(id).orElseThrow().completedAt()).isNotNull();
        StepRow b = steps.findByWorkflowAndKey(id, "B").orElseThrow();
        assertThat(b.status()).isEqualTo("COMPLETED");
        assertThat(b.output().path("message").asText()).isEqualTo("b");
    }

    @Test
    void staleWorkerCompletionIsRejectedAndDiscarded() {
        UUID id = service.submit(chain("fenced", ONE_ATTEMPT)).id();
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);

        boolean accepted = completion.complete(a, "w2", Map.of("message", "intruder"));

        assertThat(accepted).isFalse();
        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("RUNNING");
        assertThat(row.output()).isNull();
    }

    @Test
    void completionFromEarlierAttemptIsRejected() {
        UUID id = service.submit(new WorkflowDefinition("reattempt", List.of(
                new StepSpec("A", "FAIL", Map.of("message", "x"), List.of(), TWO_ATTEMPTS)))).id();
        ClaimedStep firstAttempt = claimer.claim("w1", 1, 30_000).get(0);
        assertThat(completion.fail(firstAttempt, "w1", "x")).isTrue();
        ClaimedStep secondAttempt = claimer.claim("w1", 1, 30_000).get(0);

        assertThat(completion.complete(firstAttempt, "w1", Map.of())).isFalse();
        assertThat(completion.complete(secondAttempt, "w1", Map.of())).isTrue();
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void completingTwiceIsRejectedTheSecondTime() {
        service.submit(chain("twice", ONE_ATTEMPT));
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);

        assertThat(completion.complete(a, "w1", Map.of())).isTrue();
        assertThat(completion.complete(a, "w1", Map.of())).isFalse();
    }

    @Test
    void failureWithAttemptsLeftMovesStepToRetrying() {
        UUID id = service.submit(new WorkflowDefinition("retry-state", List.of(
                new StepSpec("A", "FAIL", Map.of("message", "x"), List.of(), TWO_ATTEMPTS)))).id();
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);

        assertThat(completion.fail(a, "w1", "first try")).isTrue();

        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("RETRYING");
        assertThat(row.lastError()).isEqualTo("first try");
        assertThat(workflows.findById(id).orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void failureOnLastAttemptFailsStepAndWorkflow() {
        UUID id = service.submit(new WorkflowDefinition("exhausted", List.of(
                new StepSpec("A", "FAIL", Map.of("message", "x"), List.of(), TWO_ATTEMPTS)))).id();
        completion.fail(claimer.claim("w1", 1, 30_000).get(0), "w1", "first");
        ClaimedStep second = claimer.claim("w1", 1, 30_000).get(0);

        assertThat(completion.fail(second, "w1", "second")).isTrue();

        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.completedAt()).isNotNull();
        var workflow = workflows.findById(id).orElseThrow();
        assertThat(workflow.status()).isEqualTo("FAILED");
        assertThat(workflow.error()).isEqualTo("step 'A' failed: second");
    }

    @Test
    void workflowStaysFailedWhenParallelBranchCompletesLater() {
        UUID id = service.submit(new WorkflowDefinition("fork", List.of(
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of(), ONE_ATTEMPT),
                new StepSpec("C", "LOG", Map.of("message", "c"), List.of(), ONE_ATTEMPT)))).id();
        List<ClaimedStep> both = claimer.claim("w1", 2, 30_000);
        ClaimedStep b = both.stream().filter(s -> s.stepKey().equals("B")).findFirst().orElseThrow();
        ClaimedStep c = both.stream().filter(s -> s.stepKey().equals("C")).findFirst().orElseThrow();

        completion.fail(b, "w1", "broken");
        assertThat(completion.complete(c, "w1", Map.of())).isTrue();

        assertThat(workflows.findById(id).orElseThrow().status()).isEqualTo("FAILED");
    }

    private static WorkflowDefinition chain(String name, RetryPolicy retry) {
        return new WorkflowDefinition(name, List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry),
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of("A"), retry)));
    }
}
