package com.example.workflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.workflow.config.EngineProperties;
import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.AttemptRepository;
import com.example.workflow.persistence.AttemptRow;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRepository;
import com.example.workflow.worker.ClaimedStep;
import com.example.workflow.worker.LeaseReaper;
import com.example.workflow.worker.StepClaimer;
import com.example.workflow.worker.StepCompletion;
import com.example.workflow.worker.StepExecutor;
import com.example.workflow.worker.WorkerLoop;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs workflows end to end through claim, execute, complete, reap and retry.
 * The built-in worker is disabled; each test starts its own in-process workers.
 */
@SpringBootTest(properties = {
        "engine.worker.enabled=false",
        "engine.worker.lease=2s",
        "engine.worker.threads=2"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class WorkflowEndToEndTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long LEASE_MS = 2000;
    private static final RetryPolicy NO_BACKOFF = new RetryPolicy(3, 0, 1.0, 0);

    @Autowired
    WorkflowService service;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    StepRepository steps;

    @Autowired
    AttemptRepository attempts;

    @Autowired
    StepClaimer claimer;

    @Autowired
    StepExecutor executor;

    @Autowired
    StepCompletion completion;

    @Autowired
    LeaseReaper reaper;

    @Autowired
    EngineProperties props;

    @Autowired
    JdbcClient jdbc;

    private final List<Poller> pollers = new ArrayList<>();

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE workflow CASCADE").update();
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        for (Poller poller : pollers) {
            poller.close();
        }
        pollers.clear();
    }

    @Test
    void sequentialStepsRunInDependencyOrder() {
        UUID id = service.submit(new WorkflowDefinition("sequential", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), NO_BACKOFF),
                step("B", "LOG", Map.of("message", "b"), List.of("A"), NO_BACKOFF),
                step("C", "LOG", Map.of("message", "c"), List.of("B"), NO_BACKOFF)))).id();
        new Poller();
        new Poller();

        awaitStatus(id, "COMPLETED");

        assertThat(step(id, "A").completedAt()).isBeforeOrEqualTo(step(id, "B").startedAt());
        assertThat(step(id, "B").completedAt()).isBeforeOrEqualTo(step(id, "C").startedAt());
    }

    @Test
    void independentStepsRunInParallel() {
        UUID id = service.submit(new WorkflowDefinition("parallel", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), NO_BACKOFF),
                step("B", "SLEEP", Map.of("durationMs", 1000), List.of("A"), NO_BACKOFF),
                step("C", "SLEEP", Map.of("durationMs", 1000), List.of("A"), NO_BACKOFF)))).id();
        new Poller();
        new Poller();

        awaitStatus(id, "COMPLETED");

        StepRow b = step(id, "B");
        StepRow c = step(id, "C");
        assertThat(b.startedAt()).isBefore(c.completedAt());
        assertThat(c.startedAt()).isBefore(b.completedAt());
    }

    @Test
    void failedAttemptIsRetriedAfterBackoff() {
        UUID id = service.submit(new WorkflowDefinition("flaky", List.of(
                step("A", "FLAKY", Map.of(), List.of(), new RetryPolicy(3, 500, 2.0, 5000))))).id();
        new Poller();

        awaitStatus(id, "COMPLETED");

        List<AttemptRow> rows = attemptsOf(id, "A");
        assertThat(rows).extracting(AttemptRow::status).containsExactly("FAILED", "SUCCEEDED");
        // Backoff is 500 ms with -20% jitter; the margin covers the claim transaction.
        long gapMs = Duration.between(rows.get(0).finishedAt(), rows.get(1).startedAt()).toMillis();
        assertThat(gapMs).isGreaterThanOrEqualTo(350);
        assertThat(step(id, "A").output().get("totalAttempts").asInt()).isEqualTo(2);
    }

    @Test
    void stepFailsWorkflowWhenRetriesAreExhausted() {
        UUID id = service.submit(new WorkflowDefinition("exhaust", List.of(
                step("A", "FAIL", Map.of("message", "boom"), List.of(), new RetryPolicy(2, 100, 2.0, 1000))))).id();
        new Poller();

        awaitStatus(id, "FAILED");

        assertThat(step(id, "A").status()).isEqualTo("FAILED");
        assertThat(attemptsOf(id, "A")).extracting(AttemptRow::status).containsExactly("FAILED", "FAILED");
        assertThat(workflows.findById(id).orElseThrow().error()).contains("boom");
    }

    @Test
    void manualRetryCompletesFailedWorkflow() {
        UUID id = service.submit(new WorkflowDefinition("manual-retry", List.of(
                step("A", "FLAKY", Map.of("failUntilAttempt", 3), List.of(),
                        new RetryPolicy(2, 100, 2.0, 1000))))).id();
        new Poller();
        awaitStatus(id, "FAILED");

        service.retry(id);

        awaitStatus(id, "COMPLETED");
        assertThat(attemptsOf(id, "A")).extracting(AttemptRow::status)
                .containsExactly("FAILED", "FAILED", "SUCCEEDED");
        assertThat(step(id, "A").output().get("totalAttempts").asInt()).isEqualTo(3);
    }

    @Test
    void stepHeldByCrashedWorkerIsRecoveredByAnotherWorker() {
        UUID id = service.submit(new WorkflowDefinition("crash", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), NO_BACKOFF)))).id();
        // Simulated crash: the claim is never renewed or completed.
        claimer.claim("crashed-worker", 1, LEASE_MS);
        new Poller();
        new Poller();

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
                .until(() -> reaper.reapExpired() > 0);
        awaitStatus(id, "COMPLETED");

        List<AttemptRow> rows = attemptsOf(id, "A");
        assertThat(rows).extracting(AttemptRow::status).containsExactly("TIMED_OUT", "SUCCEEDED");
        assertThat(rows.get(0).workerId()).isEqualTo("crashed-worker");
        assertThat(rows.get(1).workerId()).isNotEqualTo("crashed-worker");
    }

    @Test
    void persistedWorkflowResumesAfterWorkerRestart() {
        UUID id = service.submit(new WorkflowDefinition("restart", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), NO_BACKOFF),
                step("B", "LOG", Map.of("message", "b"), List.of("A"), NO_BACKOFF)))).id();
        // The first worker finishes A and goes away before B is claimed.
        ClaimedStep first = claimer.claim("old-worker", 1, LEASE_MS).get(0);
        assertThat(completion.complete(first, "old-worker", Map.of("value", 42))).isTrue();
        assertThat(workflows.findById(id).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(step(id, "B").status()).isEqualTo("PENDING");

        new Poller();

        awaitStatus(id, "COMPLETED");
        assertThat(step(id, "A").output().get("value").asInt()).isEqualTo(42);
        assertThat(attemptsOf(id, "A")).extracting(AttemptRow::workerId).containsExactly("old-worker");
        assertThat(attemptsOf(id, "B")).extracting(AttemptRow::workerId).doesNotContain("old-worker");
    }

    private void awaitStatus(UUID id, String status) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> workflows.findById(id).orElseThrow().status().equals(status));
    }

    private StepRow step(UUID workflowId, String key) {
        return steps.findByWorkflowAndKey(workflowId, key).orElseThrow();
    }

    private List<AttemptRow> attemptsOf(UUID workflowId, String key) {
        UUID stepId = step(workflowId, key).id();
        return attempts.findByWorkflow(workflowId).stream()
                .filter(a -> a.stepId().equals(stepId))
                // attemptNo restarts after a manual retry, so order chronologically.
                .sorted(Comparator.comparing(AttemptRow::startedAt)
                        .thenComparingInt(AttemptRow::attemptNo))
                .toList();
    }

    private static StepSpec step(String id, String type, Map<String, Object> input,
                                 List<String> deps, RetryPolicy retry) {
        return new StepSpec(id, type, input, deps, retry);
    }

    /** An in-process worker: its own pool, polled on a fixed tick, with a distinct worker id. */
    private final class Poller {

        private final ExecutorService pool = Executors.newFixedThreadPool(props.worker().threads());
        private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        private final WorkerLoop loop = new WorkerLoop(claimer, executor, pool, props);

        Poller() {
            ticker.scheduleWithFixedDelay(loop::poll, 0, 50, TimeUnit.MILLISECONDS);
            pollers.add(this);
        }

        void close() throws InterruptedException {
            ticker.shutdownNow();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
