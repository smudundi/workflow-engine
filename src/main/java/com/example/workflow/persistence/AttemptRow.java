package com.example.workflow.persistence;

import java.time.Instant;
import java.util.UUID;

public record AttemptRow(
        UUID stepId,
        int attemptNo,
        String workerId,
        String status,
        String error,
        Instant startedAt,
        Instant finishedAt) {
}
