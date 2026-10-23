package com.example.workflow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Stopping a worker drains its in-flight step and claims nothing new. */
@SpringBootTest(properties = "engine.worker.poll-interval-ms=50")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class WorkerShutdownTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy ONE_ATTEMPT = new RetryPolicy(1, 0, 1.0, 0);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    WorkflowService service;

    @Autowired
    WorkflowRepository workflows;

    @Autowired
    StepRepository steps;

    @Autowired
    WorkerLoop worker;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE workflow CASCADE");
    }

    @Test
    void stopDrainsInFlightStepAndClaimsNothingNew() throws InterruptedException {
        UUID running = service.submit(new WorkflowDefinition("slow", List.of(
                step("A", "SLEEP", Map.of("durationMs", 2000), List.of(), ONE_ATTEMPT)))).id();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> step(running, "A").status().equals("RUNNING"));

        worker.stop();

        assertThat(step(running, "A").status()).isEqualTo("COMPLETED");
        assertThat(workflows.findById(running).orElseThrow().status()).isEqualTo("COMPLETED");

        UUID later = service.submit(new WorkflowDefinition("later", List.of(
                step("A", "LOG", Map.of("message", "a"), List.of(), ONE_ATTEMPT)))).id();
        await().during(Duration.ofSeconds(1)).atMost(TIMEOUT)
                .until(() -> workflows.findById(later).orElseThrow().status().equals("PENDING"));
    }

    private StepRow step(UUID workflowId, String key) {
        return steps.findByWorkflowAndKey(workflowId, key).orElseThrow();
    }

    private static StepSpec step(String id, String type, Map<String, Object> input,
                                 List<String> deps, RetryPolicy retry) {
        return new StepSpec(id, type, input, deps, retry);
    }
}
