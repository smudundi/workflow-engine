package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import org.springframework.stereotype.Component;

@Component
public class FailStepHandler implements StepHandler {

    @Override
    public String type() {
        return "FAIL";
    }

    @Override
    public StepResult execute(StepContext context) {
        String message = context.input().get("message") instanceof String text ? text : "Step failed";
        throw new StepFailedException(message);
    }
}
