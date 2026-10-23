package com.example.workflow.domain;

import java.util.List;

public record WorkflowDefinition(String name, List<StepSpec> steps) {
}
