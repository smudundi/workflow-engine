package com.example.workflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class WorkflowSubmitRollbackTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RetryPolicy RETRY = new RetryPolicy(3, 1000, 2.0, 30_000);

    @Autowired
    WorkflowService service;

    @Autowired
    JdbcTemplate jdbc;

    // Fails the dependency phase, after the workflow and step rows were already inserted.
    @SpyBean
    StepRepository steps;

    @Test
    void failedSubmitRollsBackWorkflowAndStepRows() {
        doThrow(new DataAccessResourceFailureException("simulated failure"))
                .when(steps).insertDependency(any(), any(), any());
        WorkflowDefinition definition = new WorkflowDefinition("rollback", List.of(
                new StepSpec("A", "LOG", Map.of(), List.of(), RETRY),
                new StepSpec("B", "LOG", Map.of(), List.of("A"), RETRY)));

        assertThatThrownBy(() -> service.submit(definition))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(count("workflow")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("step_dependency")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
