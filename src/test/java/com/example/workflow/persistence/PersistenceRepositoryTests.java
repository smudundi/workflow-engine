package com.example.workflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PersistenceRepositoryTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy RETRY = new RetryPolicy(3, 1000, 2.0, 30_000);

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

    private static StepSpec step(String id, List<String> deps) {
        return new StepSpec(id, "LOG", Map.of("message", id), deps, RETRY);
    }

    private static WorkflowDefinition diamond(String name) {
        return new WorkflowDefinition(name, List.of(
                step("A", List.of()),
                step("B", List.of("A")),
                step("C", List.of("A")),
                step("D", List.of("B", "C"))));
    }

    @Test
    void submitStoresWorkflowRow() {
        UUID id = service.submit(diamond("persist")).id();

        assertThat(workflows.findById(id)).hasValueSatisfying(w -> {
            assertThat(w.name()).isEqualTo("persist");
            assertThat(w.status()).isEqualTo("PENDING");
        });
    }

    @Test
    void submitStoresStepsWithRemainingDependencyCounts() {
        UUID id = service.submit(diamond("counts")).id();

        assertThat(jdbc.queryForList(
                "SELECT step_key || ':' || remaining_deps FROM step WHERE workflow_id = ? ORDER BY step_key",
                String.class, id))
                .containsExactly("A:0", "B:1", "C:1", "D:2");
    }

    @Test
    void submitStoresRetryPolicyAndInput() {
        UUID id = service.submit(diamond("retry")).id();

        assertThat(jdbc.queryForObject(
                "SELECT max_attempts || ':' || backoff_initial_ms || ':' || backoff_max_ms"
                        + " FROM step WHERE workflow_id = ? AND step_key = 'A'",
                String.class, id)).isEqualTo("3:1000:30000");
        assertThat(jdbc.queryForObject(
                "SELECT input->>'message' FROM step WHERE workflow_id = ? AND step_key = 'B'",
                String.class, id)).isEqualTo("B");
    }

    @Test
    void submitStoresDependencyRows() {
        UUID id = service.submit(diamond("deps")).id();

        assertThat(jdbc.queryForList(
                "SELECT step_key || ' needs ' || depends_on_key FROM step_dependency WHERE workflow_id = ?",
                String.class, id))
                .containsExactlyInAnyOrder("B needs A", "C needs A", "D needs B", "D needs C");
    }

    @Test
    void findByWorkflowReturnsStepsInKeyOrder() {
        UUID id = service.submit(diamond("ordered")).id();

        assertThat(steps.findByWorkflow(id))
                .extracting(StepRow::stepKey, StepRow::status)
                .containsExactly(tuple("A", "PENDING"), tuple("B", "PENDING"),
                        tuple("C", "PENDING"), tuple("D", "PENDING"));
    }

    @Test
    void findByWorkflowAndKeyReturnsEmptyForUnknownStep() {
        UUID id = service.submit(diamond("lookup")).id();

        assertThat(steps.findByWorkflowAndKey(id, "missing")).isEmpty();
        assertThat(steps.findByWorkflowAndKey(id, "B")).hasValueSatisfying(s -> {
            assertThat(s.stepKey()).isEqualTo("B");
            assertThat(s.output()).isNull();
        });
    }

    @Test
    void storedStepOutputIsReadBackAsJson() {
        UUID id = service.submit(diamond("output")).id();
        jdbc.update("UPDATE step SET output = CAST(? AS jsonb) WHERE workflow_id = ? AND step_key = 'A'",
                "{\"rows\": 3}", id);

        StepRow step = steps.findByWorkflowAndKey(id, "A").orElseThrow();

        assertThat(step.output().get("rows").asInt()).isEqualTo(3);
    }

    @Test
    void findByIdReturnsEmptyForUnknownWorkflow() {
        assertThat(workflows.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void attemptsAreReturnedOldestFirstAndScopedToWorkflow() {
        UUID id = service.submit(diamond("attempts")).id();
        UUID other = service.submit(diamond("other")).id();
        UUID stepA = steps.findByWorkflowAndKey(id, "A").orElseThrow().id();
        jdbc.update("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status, error, finished_at)"
                + " VALUES (?, 1, 'w1', 'FAILED', 'boom', now())", stepA);
        jdbc.update("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status, finished_at)"
                + " VALUES (?, 2, 'w1', 'SUCCEEDED', now())", stepA);

        List<AttemptRow> rows = attempts.findByWorkflow(id);

        assertThat(rows)
                .extracting(AttemptRow::attemptNo, AttemptRow::status)
                .containsExactly(tuple(1, "FAILED"), tuple(2, "SUCCEEDED"));
        assertThat(rows).allSatisfy(a -> assertThat(a.stepId()).isEqualTo(stepA));
        assertThat(attempts.findByWorkflow(other)).isEmpty();
    }

    @Test
    void duplicateStepKeyWithinWorkflowIsRejected() {
        UUID id = workflows.insert("dup", "PENDING", "{}");
        steps.insertStep(id, "A", "LOG", "{}", 0, RETRY);

        assertThatThrownBy(() -> steps.insertStep(id, "A", "LOG", "{}", 0, RETRY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativeRemainingDepsIsRejected() {
        UUID id = workflows.insert("negative", "PENDING", "{}");

        assertThatThrownBy(() -> steps.insertStep(id, "A", "LOG", "{}", -1, RETRY))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void selfDependencyIsRejected() {
        UUID id = workflows.insert("self", "PENDING", "{}");
        steps.insertStep(id, "A", "LOG", "{}", 0, RETRY);

        assertThatThrownBy(() -> steps.insertDependency(id, "A", "A"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dependencyOnStepOfAnotherWorkflowIsRejected() {
        UUID first = workflows.insert("first", "PENDING", "{}");
        steps.insertStep(first, "A", "LOG", "{}", 0, RETRY);
        UUID second = workflows.insert("second", "PENDING", "{}");
        steps.insertStep(second, "A", "LOG", "{}", 0, RETRY);
        steps.insertStep(second, "B", "LOG", "{}", 0, RETRY);

        assertThatThrownBy(() -> steps.insertDependency(first, "A", "B"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidWorkflowStatusIsRejected() {
        UUID id = workflows.insert("status", "PENDING", "{}");

        assertThatThrownBy(() -> jdbc.update("UPDATE workflow SET status = 'BOGUS' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidAttemptStatusIsRejected() {
        UUID id = workflows.insert("attempt-status", "PENDING", "{}");
        steps.insertStep(id, "A", "LOG", "{}", 0, RETRY);
        UUID stepA = steps.findByWorkflowAndKey(id, "A").orElseThrow().id();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO step_attempt (step_id, attempt_no, worker_id, status) VALUES (?, 1, 'w1', 'BOGUS')",
                stepA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingWorkflowCascadesToStepsAndDependencies() {
        UUID id = service.submit(diamond("cascade")).id();

        jdbc.update("DELETE FROM workflow WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM step WHERE workflow_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM step_dependency WHERE workflow_id = ?", Integer.class, id))
                .isZero();
    }
}
