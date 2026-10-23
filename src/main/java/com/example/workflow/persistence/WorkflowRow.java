package com.example.workflow.persistence;

import java.time.Instant;
import java.util.UUID;

public record WorkflowRow(
        UUID id,
        String name,
        String status,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}
