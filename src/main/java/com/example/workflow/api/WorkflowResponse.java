package com.example.workflow.api;

import com.example.workflow.engine.WorkflowService.StepDetail;
import com.example.workflow.engine.WorkflowService.WorkflowDetail;
import com.example.workflow.persistence.AttemptRow;
import com.example.workflow.persistence.StepRow;
import com.example.workflow.persistence.WorkflowRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        String name,
        String status,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<StepResponse> steps) {

    public static WorkflowResponse from(WorkflowDetail detail) {
        WorkflowRow w = detail.workflow();
        return new WorkflowResponse(w.id(), w.name(), w.status(), w.error(), w.createdAt(),
                w.startedAt(), w.completedAt(),
                detail.steps().stream().map(StepResponse::from).toList());
    }

    public record StepResponse(
            String stepId,
            String type,
            String status,
            int attempt,
            int maxAttempts,
            JsonNode output,
            String lastError,
            Instant nextRunAt,
            Instant startedAt,
            Instant completedAt,
            List<AttemptResponse> attempts) {

        public static StepResponse from(StepDetail detail) {
            StepRow s = detail.step();
            return new StepResponse(s.stepKey(), s.type(), s.status(), s.attempt(), s.maxAttempts(),
                    s.output(), s.lastError(), s.nextRunAt(), s.startedAt(), s.completedAt(),
                    detail.attempts().stream().map(AttemptResponse::from).toList());
        }
    }

    public record AttemptResponse(
            int attemptNo,
            String workerId,
            String status,
            String error,
            Instant startedAt,
            Instant finishedAt) {

        static AttemptResponse from(AttemptRow a) {
            return new AttemptResponse(a.attemptNo(), a.workerId(), a.status(), a.error(),
                    a.startedAt(), a.finishedAt());
        }
    }
}
