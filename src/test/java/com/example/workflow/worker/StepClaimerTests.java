package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
class StepClaimerTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy RETRY = new RetryPolicy(3, 0, 1.0, 0);

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
    void claimsOnlyStepsWhoseDependenciesAreDone() {
        UUID id = service.submit(diamond()).id();

        List<ClaimedStep> claimed = claimer.claim("w1", 10, 30_000);

        assertThat(claimed).extracting(ClaimedStep::stepKey).containsExactly("A");
        assertThat(claimed.get(0).attempt()).isEqualTo(1);
        assertThat(steps.findByWorkflowAndKey(id, "A").orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(steps.findByWorkflowAndKey(id, "B").orElseThrow().status()).isEqualTo("PENDING");
        assertThat(workflows.findById(id).orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void recordsOneRunningAttemptPerClaim() {
        UUID id = service.submit(diamond()).id();

        claimer.claim("w1", 10, 30_000);

        var attempts = jdbc.queryForList(
                "SELECT a.worker_id, a.status FROM step_attempt a JOIN step s ON s.id = a.step_id "
                        + "WHERE s.workflow_id = ?", id);
        assertThat(attempts).singleElement().satisfies(row -> {
            assertThat(row.get("worker_id")).isEqualTo("w1");
            assertThat(row.get("status")).isEqualTo("RUNNING");
        });
    }

    @Test
    void respectsTheClaimLimit() {
        service.submit(new WorkflowDefinition("pair", List.of(
                new StepSpec("X", "LOG", Map.of("message", "x"), List.of(), RETRY),
                new StepSpec("Y", "LOG", Map.of("message", "y"), List.of(), RETRY))));

        assertThat(claimer.claim("w1", 1, 30_000)).hasSize(1);
        assertThat(claimer.claim("w1", 1, 30_000)).hasSize(1);
        assertThat(claimer.claim("w1", 1, 30_000)).isEmpty();
    }

    @Test
    void zeroLimitClaimsNothing() {
        service.submit(diamond());

        assertThat(claimer.claim("w1", 0, 30_000)).isEmpty();
    }

    @Test
    void dependentClaimCarriesUpstreamOutputs() {
        UUID id = service.submit(diamond()).id();
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);
        completion.complete(a, "w1", Map.of("message", "from A"));

        List<ClaimedStep> next = claimer.claim("w1", 10, 30_000);

        assertThat(next).extracting(ClaimedStep::stepKey).containsExactlyInAnyOrder("B", "C");
        assertThat(next.get(0).upstreamOutputs()).containsOnlyKeys("A");
        assertThat(next.get(0).upstreamOutputs().get("A")).isEqualTo(Map.of("message", "from A"));
    }

    @Test
    void concurrentClaimersClaimEachStepExactlyOnce() throws Exception {
        List<StepSpec> specs = IntStream.range(0, 40)
                .mapToObj(i -> new StepSpec("S" + i, "LOG", Map.of("message", "m"), List.of(), RETRY))
                .toList();
        UUID id = service.submit(new WorkflowDefinition("wide", specs)).id();
        int claimers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int w = 0; w < claimers; w++) {
                String workerId = "w" + w;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> keys = new ArrayList<>();
                    List<ClaimedStep> batch = claimer.claim(workerId, 3, 30_000);
                    while (!batch.isEmpty()) {
                        batch.forEach(step -> keys.add(step.stepKey()));
                        batch = claimer.claim(workerId, 3, 30_000);
                    }
                    return keys;
                }));
            }
            start.countDown();
            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(all).hasSize(40).doesNotHaveDuplicates();
            Integer attempts = jdbc.queryForObject(
                    "SELECT count(*) FROM step_attempt a JOIN step s ON s.id = a.step_id WHERE s.workflow_id = ?",
                    Integer.class, id);
            assertThat(attempts).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
    }

    private static WorkflowDefinition diamond() {
        return new WorkflowDefinition("diamond", List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), RETRY),
                new StepSpec("B", "LOG", Map.of("message", "b"), List.of("A"), RETRY),
                new StepSpec("C", "LOG", Map.of("message", "c"), List.of("A"), RETRY),
                new StepSpec("D", "LOG", Map.of("message", "d"), List.of("B", "C"), RETRY)));
    }
}
