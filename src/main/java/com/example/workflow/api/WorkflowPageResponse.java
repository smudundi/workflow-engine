package com.example.workflow.api;

import com.example.workflow.engine.WorkflowService.WorkflowPage;
import com.example.workflow.persistence.WorkflowRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowPageResponse(List<Summary> content, int page, int size, long totalElements) {

    public static WorkflowPageResponse from(WorkflowPage page) {
        return new WorkflowPageResponse(page.items().stream().map(Summary::from).toList(),
                page.page(), page.size(), page.totalElements());
    }

    public record Summary(
            UUID id,
            String name,
            String status,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {

        static Summary from(WorkflowRow w) {
            return new Summary(w.id(), w.name(), w.status(), w.error(), w.createdAt(),
                    w.startedAt(), w.completedAt());
        }
    }
}
