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
import java.io.IOException;
import java.net.ServerSocket;
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

/** Runs FLAKY and HTTP steps through the real worker against PostgreSQL. */
@SpringBootTest(properties = "engine.worker.poll-interval-ms=50")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class FlakyAndHttpExecutionTests {

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
    void flakyStepFailsUntilManualRetryReachesTotalAttemptThreshold() {
        UUID id = service.submit(new WorkflowDefinition("flaky", List.of(
                new StepSpec("A", "FLAKY", Map.of("failUntilAttempt", 3), List.of(), TWO_ATTEMPTS)))).id();

        awaitStatus(id, "FAILED");
        assertThat(workflows.findById(id).orElseThrow().error()).contains("transient failure on total attempt 2");

        service.retry(id);
        awaitStatus(id, "COMPLETED");

        // Attempt number restarted at 1 on retry, but the total counts all three claims.
        assertThat(step(id, "A").output().path("totalAttempts").asInt()).isEqualTo(3);
        assertThat(attempts.findByWorkflow(id)).hasSize(3);
    }

    @Test
    void httpStepToUnreachableUrlFailsWorkflowAfterSingleAttempt() throws IOException {
        String url = "http://127.0.0.1:" + closedPort() + "/health";
        UUID id = service.submit(new WorkflowDefinition("http", List.of(
                new StepSpec("A", "HTTP", Map.of("url", url, "timeoutMs", 2000), List.of(), ONE_ATTEMPT)))).id();

        awaitStatus(id, "FAILED");

        assertThat(workflows.findById(id).orElseThrow().error()).contains("HTTP request to " + url + " failed");
        assertThat(step(id, "A").status()).isEqualTo("FAILED");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50))
                .until(() -> workflows.findById(id).orElseThrow().status().equals(expected));
    }

    private StepRow step(UUID workflowId, String key) {
        return steps.findByWorkflowAndKey(workflowId, key).orElseThrow();
    }
}
