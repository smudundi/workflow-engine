package com.example.workflow.api;

import com.example.workflow.api.SubmitWorkflowRequest.RetryRequest;
import com.example.workflow.api.SubmitWorkflowRequest.StepRequest;
import com.example.workflow.config.EngineProperties;
import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.engine.WorkflowService.SubmittedWorkflow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
@Profile({"api", "all"})
public class WorkflowController {

    private final WorkflowService service;
    private final EngineProperties.Retry retryDefaults;

    public WorkflowController(WorkflowService service, EngineProperties properties) {
        this.service = service;
        this.retryDefaults = properties.retry();
    }

    @PostMapping
    public ResponseEntity<SubmitWorkflowResponse> submit(@Valid @RequestBody SubmitWorkflowRequest request) {
        SubmittedWorkflow submitted = service.submit(toDefinition(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubmitWorkflowResponse(submitted.id(), submitted.status().name()));
    }

    private WorkflowDefinition toDefinition(SubmitWorkflowRequest request) {
        List<StepSpec> steps = request.steps().stream()
                .map(this::toStepSpec)
                .toList();
        return new WorkflowDefinition(request.name(), steps);
    }

    private StepSpec toStepSpec(StepRequest step) {
        Map<String, Object> input = Objects.requireNonNullElse(step.input(), Map.of());
        List<String> dependsOn = Objects.requireNonNullElse(step.dependsOn(), List.of());
        return new StepSpec(step.id(), step.type(), input, dependsOn, resolveRetry(step.retry()));
    }

    private RetryPolicy resolveRetry(RetryRequest retry) {
        RetryRequest r = Objects.requireNonNullElse(retry, new RetryRequest(null, null, null, null));
        return new RetryPolicy(
                Objects.requireNonNullElse(r.maxAttempts(), retryDefaults.defaultMaxAttempts()),
                Objects.requireNonNullElse(r.initialDelayMs(), retryDefaults.defaultInitialDelay().toMillis()),
                Objects.requireNonNullElse(r.multiplier(), retryDefaults.defaultMultiplier()),
                Objects.requireNonNullElse(r.maxDelayMs(), retryDefaults.defaultMaxDelay().toMillis()));
    }
}
