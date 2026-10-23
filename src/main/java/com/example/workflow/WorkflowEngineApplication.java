package com.example.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkflowEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowEngineApplication.class, args);
    }
}
