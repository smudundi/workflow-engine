package com.example.workflow.worker;

import java.util.Map;
import java.util.UUID;

/** A step claimed by a worker; {@code attempt} is the attempt number of this claim. */
public record ClaimedStep(
        UUID id,
        UUID workflowId,
        String stepKey,
        String type,
        Map<String, Object> input,
        Map<String, Object> upstreamOutputs,
        int attempt,
        int maxAttempts) {
}
