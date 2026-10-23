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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
class LeaseHeartbeatTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy RETRY = new RetryPolicy(3, 0, 1.0, 0);
    private static final RetryPolicy ONE_ATTEMPT = new RetryPolicy(1, 0, 1.0, 0);

    @Autowired
    WorkflowService service;

    @Autowired
    LeaseHeartbeat heartbeat;

    @Autowired
    StepClaimer claimer;

    @Autowired
    StepCompletion completion;

    @Autowired
    StepExecutor executor;

    @Autowired
    StepRepository steps;

    @Autowired
    JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void renewalExtendsTheLeaseOfTheCurrentAttempt() {
        service.submit(single("renew", ONE_ATTEMPT));
        ClaimedStep a = claimer.claim("w1", 1, 30_000).get(0);
        jdbc.update("UPDATE step SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", a.id());

        assertThat(heartbeat.renew(a, "w1", 30_000)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT lease_expires_at > now() FROM step WHERE id = ?", Boolean.class, a.id())).isTrue();
    }

    @Test
    void reclaimedStepFencesTheOldWorkerOff() {
        UUID id = service.submit(single("stolen", RETRY)).id();
        ClaimedStep first = claimer.claim("w1", 1, 30_000).get(0);
        // Stands in for the lease reaper (US2.3): expire the lease and requeue the step.
        jdbc.update("UPDATE step SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", first.id());
        jdbc.update("UPDATE step SET status = 'RETRYING', lease_owner = NULL, lease_expires_at = NULL, "
                + "next_run_at = now() WHERE id = ?", first.id());
        ClaimedStep second = claimer.claim("w2", 1, 30_000).get(0);

        assertThat(heartbeat.renew(first, "w1", 30_000)).isFalse();
        assertThat(completion.complete(first, "w1", Map.of("message", "stale"))).isFalse();
        assertThat(heartbeat.renew(second, "w2", 30_000)).isTrue();
        assertThat(completion.complete(second, "w2", Map.of("message", "fresh"))).isTrue();

        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.output().path("message").asText()).isEqualTo("fresh");
    }

    @Test
    void handlerIsInterruptedAndResultDiscardedWhenLeaseIsStolen() throws Exception {
        UUID id = service.submit(new WorkflowDefinition("sleepy", List.of(
                new StepSpec("A", "SLEEP", Map.of("durationMs", 10_000), List.of(), ONE_ATTEMPT)))).id();
        ClaimedStep a = claimer.claim("w1", 1, 300).get(0);
        Future<?> run = pool.submit(() -> executor.run(a, "w1", 300));

        jdbc.update("UPDATE step SET lease_owner = 'w2', attempt = attempt + 1 WHERE id = ?", a.id());

        // Returns only if the heartbeat interrupted the 10 s sleep.
        run.get(5, TimeUnit.SECONDS);
        StepRow row = steps.findByWorkflowAndKey(id, "A").orElseThrow();
        assertThat(row.status()).isEqualTo("RUNNING");
        assertThat(row.output()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM step WHERE id = ?", String.class, a.id()))
                .isEqualTo("w2");
    }

    private static WorkflowDefinition single(String name, RetryPolicy retry) {
        return new WorkflowDefinition(name, List.of(
                new StepSpec("A", "LOG", Map.of("message", "a"), List.of(), retry)));
    }
}
