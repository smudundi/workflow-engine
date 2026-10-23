package com.example.workflow.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record StepRow(
        UUID id,
        String stepKey,
        String type,
        String status,
        int attempt,
        int maxAttempts,
        JsonNode output,
        String lastError,
        Instant nextRunAt,
        Instant startedAt,
        Instant completedAt) {
}
