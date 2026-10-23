package com.example.workflow.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record SubmitWorkflowRequest(
        @Schema(description = "Workflow name", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        @NotBlank @Size(max = 200) String name,
        @Schema(description = "Steps forming a DAG via dependsOn", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<@Valid StepRequest> steps) {

    public record StepRequest(
            @Schema(description = "Unique step key within the workflow", maxLength = 100)
            @NotBlank @Size(max = 100) String id,
            @Schema(description = "Step type: LOG, SLEEP, TRANSFORM or FAIL", example = "SLEEP")
            @NotBlank String type,
            @Schema(description = "Type-specific input, e.g. {\"durationMs\": 500}")
            Map<String, Object> input,
            @Schema(description = "Keys of steps that must complete first")
            List<@NotBlank String> dependsOn,
            @Valid RetryRequest retry) {
    }

    // Null fields fall back to the engine.retry.* defaults.
    @Schema(description = "Retry settings; omitted fields use engine defaults")
    public record RetryRequest(
            @Schema(description = "Maximum attempts, 1 to 20") Integer maxAttempts,
            @Schema(description = "Delay before the first retry, in ms") Long initialDelayMs,
            @Schema(description = "Backoff multiplier, 1 to 10") Double multiplier,
            @Schema(description = "Maximum delay between retries, in ms") Long maxDelayMs) {
    }
}
