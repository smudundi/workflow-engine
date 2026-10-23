package com.example.workflow.handler;

import java.util.Map;

public record StepContext(
        String stepId,
        int attempt,
        Map<String, Object> input,
        Map<String, Object> upstreamOutputs) {

    public String idempotencyKey() {
        return stepId + ":" + attempt;
    }
}
