package com.example.workflow.domain;

import java.util.List;

public class InvalidWorkflowException extends RuntimeException {

    private final List<String> errors;

    public InvalidWorkflowException(List<String> errors) {
        super("Invalid workflow definition: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
