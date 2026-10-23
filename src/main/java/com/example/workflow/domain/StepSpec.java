package com.example.workflow.domain;

import java.util.List;
import java.util.Map;

public record StepSpec(
        String id,
        String type,
        Map<String, Object> input,
        List<String> dependsOn,
        RetryPolicy retry) {
}
