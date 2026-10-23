package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogStepHandler implements StepHandler {

    private static final Logger log = LoggerFactory.getLogger(LogStepHandler.class);

    @Override
    public String type() {
        return "LOG";
    }

    @Override
    public StepResult execute(StepContext context) {
        if (!(context.input().get("message") instanceof String message)) {
            throw new StepFailedException("LOG step requires a string 'message'");
        }
        log.info("[{}] {}", context.stepId(), message);
        return new StepResult(Map.of("message", message));
    }
}
