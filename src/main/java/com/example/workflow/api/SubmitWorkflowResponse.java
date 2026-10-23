package com.example.workflow.api;

import java.util.UUID;

public record SubmitWorkflowResponse(UUID id, String status) {
}
