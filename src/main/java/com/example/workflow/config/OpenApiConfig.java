package com.example.workflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI workflowEngineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Workflow Engine API")
                .version("0.1.0")
                .description("Submit DAG workflows and query their step status and attempt history."));
    }
}
