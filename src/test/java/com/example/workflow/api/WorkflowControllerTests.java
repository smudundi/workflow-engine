package com.example.workflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "engine.worker.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class WorkflowControllerTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static final String VALID = """
            {
              "name": "parallel",
              "steps": [
                { "id": "A", "type": "LOG", "input": { "message": "start" } },
                { "id": "B", "type": "SLEEP", "input": { "durationMs": 3000 }, "dependsOn": ["A"] },
                { "id": "C", "type": "SLEEP", "input": { "durationMs": 3000 }, "dependsOn": ["A"] },
                { "id": "D", "type": "TRANSFORM", "input": { "op": "merge" }, "dependsOn": ["B", "C"],
                  "retry": { "maxAttempts": 2 } }
              ]
            }
            """;

    @Test
    void apiDocsDescribeEndpointsAndSampleExample() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/workflows'].post.summary").value("Submit a workflow"))
                .andExpect(jsonPath("$.paths['/api/workflows/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/workflows/{id}/steps/{stepId}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/workflows'].post.requestBody.content['application/json'].examples"
                        + "['A to (B,C) to D to E'].value.name").value("parallel-data-pipeline"));
    }

    private String submit(String body) throws Exception {
        String response = mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void validDagReturns201WithIdAndPendingStatus() throws Exception {
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void cycleReturns400WithMessage() throws Exception {
        String cyclic = """
                { "name": "loop", "steps": [
                    { "id": "A", "type": "LOG", "dependsOn": ["B"] },
                    { "id": "B", "type": "LOG", "dependsOn": ["A"] } ] }
                """;
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(cyclic))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("cycle")));
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        String unknown = """
                { "name": "x", "steps": [ { "id": "A", "type": "NOPE" } ] }
                """;
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("unknown type")));
    }

    @Test
    void getWorkflowReturnsStepsWithAttemptHistory() throws Exception {
        String id = submit(VALID);
        UUID stepId = jdbc.queryForObject(
                "SELECT id FROM step WHERE workflow_id = ? AND step_key = 'A'", UUID.class, UUID.fromString(id));
        jdbc.update("UPDATE step SET status = 'COMPLETED', attempt = 1, output = CAST(? AS jsonb) WHERE id = ?",
                "{\"message\": \"start\"}", stepId);
        jdbc.update("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status, finished_at) "
                + "VALUES (?, 1, 'worker-1', 'SUCCEEDED', now())", stepId);

        mvc.perform(get("/api/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].stepId").value("A"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].output.message").value("start"))
                .andExpect(jsonPath("$.steps[0].attempts[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$.steps[1].attempts.length()").value(0));
    }

    @Test
    void getStepReturnsSingleStep() throws Exception {
        String id = submit(VALID);

        mvc.perform(get("/api/workflows/" + id + "/steps/D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepId").value("D"))
                .andExpect(jsonPath("$.type").value("TRANSFORM"))
                .andExpect(jsonPath("$.maxAttempts").value(2));
    }

    @Test
    void unknownWorkflowReturns404() throws Exception {
        mvc.perform(get("/api/workflows/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownStepReturns404() throws Exception {
        String id = submit(VALID);

        mvc.perform(get("/api/workflows/" + id + "/steps/Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        String blankId = """
                { "name": "x", "steps": [ { "id": "", "type": "LOG" } ] }
                """;
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(blankId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("steps[0].id")));
    }

    @Test
    void retryOfFailedWorkflowReturns200AndRunning() throws Exception {
        String id = submit(VALID);
        jdbc.update("UPDATE workflow SET status = 'FAILED', error = 'step ''A'' failed: boom' WHERE id = ?",
                UUID.fromString(id));
        jdbc.update("UPDATE step SET status = 'FAILED', attempt = 1, last_error = 'boom' "
                + "WHERE workflow_id = ? AND step_key = 'A'", UUID.fromString(id));

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.steps[0].stepId").value("A"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"));
    }

    @Test
    void retryOfPendingWorkflowReturns409() throws Exception {
        String id = submit(VALID);

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("only FAILED")));
    }

    @Test
    void retryOfUnknownWorkflowReturns404() throws Exception {
        mvc.perform(post("/api/workflows/" + UUID.randomUUID() + "/retry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryOfRunningWorkflowReturns409() throws Exception {
        String id = submit(VALID);
        jdbc.update("UPDATE workflow SET status = 'RUNNING' WHERE id = ?", UUID.fromString(id));

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RUNNING")));
    }

    @Test
    void retryOfCompletedWorkflowReturns409() throws Exception {
        String id = submit(VALID);
        jdbc.update("UPDATE workflow SET status = 'COMPLETED' WHERE id = ?", UUID.fromString(id));

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("COMPLETED")));
    }

    @Test
    void retryKeepsCompletedStepAndAttemptHistoryAndResetsFailedStep() throws Exception {
        String id = submit(VALID);
        UUID wf = UUID.fromString(id);
        jdbc.update("UPDATE workflow SET status = 'FAILED', error = 'step ''B'' failed: boom' WHERE id = ?", wf);
        jdbc.update("UPDATE step SET status = 'COMPLETED', attempt = 1, output = CAST(? AS jsonb) "
                + "WHERE workflow_id = ? AND step_key = 'A'", "{\"message\": \"start\"}", wf);
        jdbc.update("UPDATE step SET status = 'FAILED', attempt = 2, last_error = 'boom' "
                + "WHERE workflow_id = ? AND step_key = 'B'", wf);
        jdbc.update("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status, finished_at) "
                + "SELECT id, 1, 'worker-1', 'SUCCEEDED', now() FROM step WHERE workflow_id = ? AND step_key = 'A'", wf);
        jdbc.update("INSERT INTO step_attempt (step_id, attempt_no, worker_id, status, error, finished_at) "
                + "SELECT id, 2, 'worker-2', 'FAILED', 'boom', now() FROM step WHERE workflow_id = ? AND step_key = 'B'", wf);

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.steps[0].stepId").value("A"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].output.message").value("start"))
                .andExpect(jsonPath("$.steps[0].attempts.length()").value(1))
                .andExpect(jsonPath("$.steps[1].stepId").value("B"))
                .andExpect(jsonPath("$.steps[1].status").value("PENDING"))
                .andExpect(jsonPath("$.steps[1].attempt").value(0))
                .andExpect(jsonPath("$.steps[1].lastError").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.steps[1].attempts.length()").value(1))
                .andExpect(jsonPath("$.steps[1].attempts[0].attemptNo").value(2))
                .andExpect(jsonPath("$.steps[1].attempts[0].status").value("FAILED"));
    }

    @Test
    void retryResetsFailedStepVisibleThroughStepEndpoint() throws Exception {
        String id = submit(VALID);
        UUID wf = UUID.fromString(id);
        jdbc.update("UPDATE workflow SET status = 'FAILED' WHERE id = ?", wf);
        jdbc.update("UPDATE step SET status = 'FAILED', attempt = 3, last_error = 'boom' "
                + "WHERE workflow_id = ? AND step_key = 'B'", wf);

        mvc.perform(post("/api/workflows/" + id + "/retry")).andExpect(status().isOk());

        mvc.perform(get("/api/workflows/" + id + "/steps/B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempt").value(0))
                .andExpect(jsonPath("$.lastError").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void secondRetryOfSameWorkflowReturns409() throws Exception {
        String id = submit(VALID);
        jdbc.update("UPDATE workflow SET status = 'FAILED' WHERE id = ?", UUID.fromString(id));

        mvc.perform(post("/api/workflows/" + id + "/retry")).andExpect(status().isOk());

        mvc.perform(post("/api/workflows/" + id + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("RUNNING")));
    }
}
