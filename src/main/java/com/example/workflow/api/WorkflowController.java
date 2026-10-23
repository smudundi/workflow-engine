package com.example.workflow.api;

import com.example.workflow.api.SubmitWorkflowRequest.RetryRequest;
import com.example.workflow.api.SubmitWorkflowRequest.StepRequest;
import com.example.workflow.config.EngineProperties;
import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.domain.WorkflowStatus;
import com.example.workflow.engine.WorkflowService;
import com.example.workflow.engine.WorkflowService.SubmittedWorkflow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
@Profile({"api", "all"})
@Tag(name = "Workflows", description = "Submit workflows and query their status")
public class WorkflowController {

    // Sample from samples/parallel.json: A runs first, B and C run in parallel, D merges, E publishes.
    private static final String SAMPLE_WORKFLOW = """
            {
              "name": "parallel-data-pipeline",
              "steps": [
                {"id": "A", "type": "SLEEP", "input": {"durationMs": 500}},
                {"id": "B", "type": "SLEEP", "input": {"durationMs": 3000}, "dependsOn": ["A"]},
                {"id": "C", "type": "SLEEP", "input": {"durationMs": 3000}, "dependsOn": ["A"]},
                {"id": "D", "type": "TRANSFORM", "input": {"op": "merge"}, "dependsOn": ["B", "C"]},
                {"id": "E", "type": "LOG", "input": {"message": "results published"}, "dependsOn": ["D"]}
              ]
            }
            """;

    private final WorkflowService service;
    private final EngineProperties.Retry retryDefaults;

    public WorkflowController(WorkflowService service, EngineProperties properties) {
        this.service = service;
        this.retryDefaults = properties.retry();
    }

    @Operation(summary = "Submit a workflow", description = "Validates the DAG and stores the workflow as PENDING.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Workflow accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid definition",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<SubmitWorkflowResponse> submit(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(name = "A to (B,C) to D to E", value = SAMPLE_WORKFLOW)))
            SubmitWorkflowRequest request) {
        SubmittedWorkflow submitted = service.submit(toDefinition(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SubmitWorkflowResponse(submitted.id(), submitted.status().name()));
    }

    @Operation(summary = "List workflows",
            description = "Newest first. Optional status filter; page is 0-based and size is 1-100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of workflows"),
            @ApiResponse(responseCode = "400", description = "Invalid status, page, or size",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public WorkflowPageResponse list(
            @RequestParam(required = false) WorkflowStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return WorkflowPageResponse.from(service.listWorkflows(status, page, size));
    }

    @Operation(summary = "Get workflow status", description = "Returns the workflow with all steps and attempt history.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workflow found"),
            @ApiResponse(responseCode = "404", description = "Unknown workflow id",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable UUID id) {
        return WorkflowResponse.from(service.getWorkflow(id));
    }

    @Operation(summary = "Get step status", description = "Returns one step with its attempt history. stepId is the step key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Step found"),
            @ApiResponse(responseCode = "404", description = "Unknown workflow id or step key",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}/steps/{stepId}")
    public WorkflowResponse.StepResponse getStep(@PathVariable UUID id, @PathVariable String stepId) {
        return WorkflowResponse.StepResponse.from(service.getStep(id, stepId));
    }

    @Operation(summary = "Retry a failed workflow",
            description = "Requeues FAILED steps and moves the workflow to RUNNING. Completed steps are not re-run.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workflow requeued"),
            @ApiResponse(responseCode = "404", description = "Unknown workflow id",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Workflow is not FAILED",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/retry")
    public WorkflowResponse retry(@PathVariable UUID id) {
        service.retry(id);
        return WorkflowResponse.from(service.getWorkflow(id));
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
