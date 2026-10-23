package com.example.workflow.handler;

import java.util.Map;

public record StepContext(
        String stepId,
        int attempt,
        int totalAttempts,
        Map<String, Object> input,
        Map<String, Object> upstreamOutputs) {

    // Without manual retry history, total attempts equal the attempt number.
    public StepContext(String stepId, int attempt, Map<String, Object> input, Map<String, Object> upstreamOutputs) {
        this(stepId, attempt, attempt, input, upstreamOutputs);
    }

    public String idempotencyKey() {
        return stepId + ":" + attempt;
    }
}
