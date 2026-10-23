package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SleepStepHandler implements StepHandler {

    @Override
    public String type() {
        return "SLEEP";
    }

    @Override
    public StepResult execute(StepContext context) {
        if (!(context.input().get("durationMs") instanceof Number number) || number.longValue() < 0) {
            throw new StepFailedException("SLEEP step requires a non-negative 'durationMs'");
        }
        long durationMs = number.longValue();
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("SLEEP step interrupted");
        }
        return new StepResult(Map.of("durationMs", durationMs));
    }
}
