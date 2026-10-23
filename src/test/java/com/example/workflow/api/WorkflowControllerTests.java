package com.example.workflow.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkflowControllerTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

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
                { "name": "x", "steps": [ { "id": "A", "type": "HTTP" } ] }
                """;
        mvc.perform(post("/api/workflows").contentType(MediaType.APPLICATION_JSON).content(unknown))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("unknown type")));
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
}
