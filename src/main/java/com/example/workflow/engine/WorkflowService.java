package com.example.workflow.engine;

import com.example.workflow.dag.DagValidator;
import com.example.workflow.domain.ConflictException;
import com.example.workflow.domain.InvalidWorkflowException;
import com.example.workflow.domain.NotFoundException;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.domain.WorkflowStatus;
import com.example.workflow.persistence.AttemptRepository;
import com.example.workflow.persistence.AttemptRow;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRepository;
import com.example.workflow.persistence.WorkflowRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {

    private final DagValidator validator;
    private final WorkflowRepository workflows;
    private final StepRepository steps;
    private final AttemptRepository attempts;
    private final ObjectMapper json;

    public WorkflowService(DagValidator validator, WorkflowRepository workflows,
                           StepRepository steps, AttemptRepository attempts, ObjectMapper json) {
        this.validator = validator;
        this.workflows = workflows;
        this.steps = steps;
        this.attempts = attempts;
        this.json = json;
    }

    public record SubmittedWorkflow(UUID id, WorkflowStatus status) {
    }

    public record StepDetail(StepRow step, List<AttemptRow> attempts) {
    }

    public record WorkflowDetail(WorkflowRow workflow, List<StepDetail> steps) {
    }

    public record WorkflowPage(List<WorkflowRow> items, int page, int size, long totalElements) {
    }

    @Transactional
    public SubmittedWorkflow submit(WorkflowDefinition definition) {
        var errors = validator.validate(definition.steps());
        if (!errors.isEmpty()) {
            throw new InvalidWorkflowException(errors);
        }

        UUID id = workflows.insert(definition.name(), WorkflowStatus.PENDING.name(), toJson(definition));
        for (StepSpec step : definition.steps()) {
            steps.insertStep(id, step.id(), step.type(), toJson(step.input()),
                    step.dependsOn().size(), step.retry());
        }
        // Dependency rows reference steps, so they are inserted after all steps exist.
        for (StepSpec step : definition.steps()) {
            for (String dep : step.dependsOn()) {
                steps.insertDependency(id, step.id(), dep);
            }
        }
        return new SubmittedWorkflow(id, WorkflowStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public WorkflowDetail getWorkflow(UUID id) {
        WorkflowRow workflow = findWorkflow(id);
        Map<UUID, List<AttemptRow>> attemptsByStep = attempts.findByWorkflow(id).stream()
                .collect(Collectors.groupingBy(AttemptRow::stepId));
        List<StepDetail> details = steps.findByWorkflow(id).stream()
                .map(step -> new StepDetail(step, attemptsByStep.getOrDefault(step.id(), List.of())))
                .toList();
        return new WorkflowDetail(workflow, details);
    }

    @Transactional(readOnly = true)
    public StepDetail getStep(UUID workflowId, String stepKey) {
        findWorkflow(workflowId);
        StepRow step = steps.findByWorkflowAndKey(workflowId, stepKey)
                .orElseThrow(() -> new NotFoundException(
                        "step '" + stepKey + "' not found in workflow " + workflowId));
        List<AttemptRow> stepAttempts = attempts.findByWorkflow(workflowId).stream()
                .filter(a -> a.stepId().equals(step.id()))
                .toList();
        return new StepDetail(step, stepAttempts);
    }

    @Transactional(readOnly = true)
    public WorkflowPage listWorkflows(WorkflowStatus status, int page, int size) {
        String filter = status == null ? null : status.name();
        List<WorkflowRow> items = workflows.findPage(filter, size, (long) page * size);
        return new WorkflowPage(items, page, size, workflows.count(filter));
    }

    /** Requeues a FAILED workflow's failed steps; completed steps are kept. */
    @Transactional
    public void retry(UUID id) {
        // Lock order matches StepCompletion: steps before the workflow row.
        steps.lockFailed(id);
        String status = workflows.lockStatus(id)
                .orElseThrow(() -> new NotFoundException("workflow " + id + " not found"));
        if (!WorkflowStatus.FAILED.name().equals(status)) {
            throw new ConflictException("workflow " + id + " is " + status + "; only FAILED workflows can be retried");
        }
        steps.resetFailed(id);
        workflows.markRunning(id);
    }

    private WorkflowRow findWorkflow(UUID id) {
        return workflows.findById(id)
                .orElseThrow(() -> new NotFoundException("workflow " + id + " not found"));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize workflow definition", e);
        }
    }
}
