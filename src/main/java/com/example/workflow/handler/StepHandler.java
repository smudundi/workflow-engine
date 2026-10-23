package com.example.workflow.handler;

public interface StepHandler {

    /** The step type this handler serves, as written in the workflow definition. */
    String type();

    /** Runs one attempt; throws StepFailedException when the attempt fails. */
    StepResult execute(StepContext context);
}
