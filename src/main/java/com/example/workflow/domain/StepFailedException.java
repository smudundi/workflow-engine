package com.example.workflow.domain;

public class StepFailedException extends RuntimeException {

    public StepFailedException(String message) {
        super(message);
    }
}
