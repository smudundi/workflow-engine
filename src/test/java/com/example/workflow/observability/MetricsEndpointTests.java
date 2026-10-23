package com.example.workflow.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "engine.worker.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class MetricsEndpointTests {

    // Needs a running Docker daemon; PostgreSQL 16 matches docker-compose.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Test
    void workerCountersAreVisibleAtActuatorMetrics() throws Exception {
        for (String name : new String[] {"claimed", "completed", "failed", "retried", "reaped"}) {
            mvc.perform(get("/actuator/metrics/workflow.steps." + name))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("workflow.steps." + name));
        }
    }
}
