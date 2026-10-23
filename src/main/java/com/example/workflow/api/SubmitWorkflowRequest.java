package com.example.workflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record SubmitWorkflowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotEmpty List<@Valid StepRequest> steps) {

    public record StepRequest(
            @NotBlank @Size(max = 100) String id,
            @NotBlank String type,
            Map<String, Object> input,
            List<@NotBlank String> dependsOn,
            @Valid RetryRequest retry) {
    }

    // Null fields fall back to the engine.retry.* defaults.
    public record RetryRequest(Integer maxAttempts, Long initialDelayMs, Double multiplier, Long maxDelayMs) {
    }
}
