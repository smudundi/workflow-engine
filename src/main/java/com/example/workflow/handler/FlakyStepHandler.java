package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FlakyStepHandler implements StepHandler {

    @Override
    public String type() {
        return "FLAKY";
    }

    @Override
    public StepResult execute(StepContext context) {
        // Defaults to failing the first attempt only.
        int failUntilAttempt = context.input().get("failUntilAttempt") instanceof Number n ? n.intValue() : 2;
        if (context.totalAttempts() < failUntilAttempt) {
            throw new StepFailedException("transient failure on total attempt " + context.totalAttempts());
        }
        return new StepResult(Map.of("totalAttempts", context.totalAttempts()));
    }
}
