package com.example.workflow.engine;

import com.example.workflow.dag.DagValidator;
import com.example.workflow.domain.InvalidWorkflowException;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.domain.WorkflowDefinition;
import com.example.workflow.domain.WorkflowStatus;
import com.example.workflow.persistence.StepRepository;
import com.example.workflow.persistence.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {

    private final DagValidator validator;
    private final WorkflowRepository workflows;
    private final StepRepository steps;
    private final ObjectMapper json;

    public WorkflowService(DagValidator validator, WorkflowRepository workflows,
                           StepRepository steps, ObjectMapper json) {
        this.validator = validator;
        this.workflows = workflows;
        this.steps = steps;
        this.json = json;
    }

    public record SubmittedWorkflow(UUID id, WorkflowStatus status) {
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

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize workflow definition", e);
        }
    }
}
