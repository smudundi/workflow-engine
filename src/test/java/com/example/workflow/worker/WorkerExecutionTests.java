package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.AttemptRepository;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real scheduled worker against PostgreSQL. */
@SpringBootTest(properties = "engine.worker.poll-interval-ms=50")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class WorkerExecutionTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy ONE_ATTEMPT = new RetryPolicy(1, 0, 1.0, 0);
    private static final RetryPolicy TWO_ATTEMPTS = new RetryPolicy(2, 0, 1.0, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    WorkflowService service;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    StepRepository steps;

    @Autowired
    AttemptRepository attempts;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @Test
    void sequentialStepsRunInDependencyOrderAndWorkflowCompletes() {
        UUID id = service.submit(new WorkflowDefinition("sequential", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), ONE_ATTEMPT),
                step("B", "LOG", Map.of("message", "b"), List.of("A"), ONE_ATTEMPT),
                step("C", "LOG", Map.of("message", "c"), List.of("B"), ONE_ATTEMPT)))).id();

        awaitStatus(id, "COMPLETED");

        StepRow a = step(id, "A");
        StepRow b = step(id, "B");
        StepRow c = step(id, "C");
        assertThat(a.completedAt()).isBeforeOrEqualTo(b.startedAt());
        assertThat(b.completedAt()).isBeforeOrEqualTo(c.startedAt());
        assertThat(workflows.findById(id).orElseThrow().completedAt()).isNotNull();
    }

    @Test
    void parallelBranchesOverlapAndJoinBeforeTheMergeStep() {
        UUID id = service.submit(new WorkflowDefinition("parallel", List.of(
                step("A", "LOG", Map.of("message", "start"), List.of(), ONE_ATTEMPT),
                step("B", "SLEEP", Map.of("durationMs", 1000), List.of("A"), ONE_ATTEMPT),
                step("C", "SLEEP", Map.of("durationMs", 1000), List.of("A"), ONE_ATTEMPT),
                step("D", "TRANSFORM", Map.of("op", "merge"), List.of("B", "C"), ONE_ATTEMPT)))).id();

        awaitStatus(id, "COMPLETED");

        StepRow b = step(id, "B");
        StepRow c = step(id, "C");
        StepRow d = step(id, "D");
        assertThat(b.startedAt()).isBeforeOrEqualTo(c.completedAt());
        assertThat(c.startedAt()).isBeforeOrEqualTo(b.completedAt());
        assertThat(d.startedAt()).isAfterOrEqualTo(b.completedAt());
        assertThat(d.startedAt()).isAfterOrEqualTo(c.completedAt());
        assertThat(d.output().path("merged").has("B")).isTrue();
        assertThat(d.output().path("merged").has("C")).isTrue();
    }

    @Test
    void failingStepWithSingleAttemptFailsWorkflowAndBlocksDependents() {
        UUID id = service.submit(new WorkflowDefinition("failing", List.of(
                step("A", "FAIL", Map.of("message", "boom"), List.of(), ONE_ATTEMPT),
                step("B", "LOG", Map.of("message", "b"), List.of("A"), ONE_ATTEMPT)))).id();

        awaitStatus(id, "FAILED");

        assertThat(workflows.findById(id).orElseThrow().error()).contains("step 'A' failed: boom");
        StepRow a = step(id, "A");
        assertThat(a.status()).isEqualTo("FAILED");
        assertThat(a.attempt()).isEqualTo(1);
        assertThat(step(id, "B").status()).isEqualTo("PENDING");
        assertThat(step(id, "B").attempt()).isZero();
    }

    @Test
    void failingStepRetriesUntilMaxAttemptsThenFailsWorkflow() {
        UUID id = service.submit(new WorkflowDefinition("retrying", List.of(
                step("A", "FAIL", Map.of("message", "still broken"), List.of(), TWO_ATTEMPTS)))).id();

        awaitStatus(id, "FAILED");

        assertThat(step(id, "A").attempt()).isEqualTo(2);
        List<String> outcomes = attempts.findByWorkflow(id).stream().map(a -> a.status()).toList();
        assertThat(outcomes).containsExactly("FAILED", "FAILED");
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> workflows.findById(id).orElseThrow().status().equals(expected));
    }

    private StepRow step(UUID workflowId, String key) {
        return steps.findByWorkflowAndKey(workflowId, key).orElseThrow();
    }

    private static StepSpec step(String id, String type, Map<String, Object> input,
                                 List<String> deps, RetryPolicy retry) {
        return new StepSpec(id, type, input, deps, retry);
    }
}
