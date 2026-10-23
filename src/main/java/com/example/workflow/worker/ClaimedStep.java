package com.example.workflow.worker;

import com.example.workflow.domain.RetryPolicy;
import java.util.Map;
import java.util.UUID;

/** A step claimed by a worker; {@code attempt} resets on manual retry, {@code totalAttempts} does not. */
public record ClaimedStep(
        UUID id,
        UUID workflowId,
        String stepKey,
        String type,
        Map<String, Object> input,
        Map<String, Object> upstreamOutputs,
        int attempt,
        int totalAttempts,
        RetryPolicy retry) {
}
