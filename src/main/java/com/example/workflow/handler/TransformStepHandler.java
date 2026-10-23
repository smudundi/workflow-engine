package com.example.workflow.handler;

import com.example.workflow.domain.StepFailedException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TransformStepHandler implements StepHandler {

    @Override
    public String type() {
        return "TRANSFORM";
    }

    @Override
    public StepResult execute(StepContext context) {
        if (!(context.input().get("op") instanceof String op)) {
            throw new StepFailedException("TRANSFORM step requires a string 'op'");
        }
        Map<String, Object> upstream = context.upstreamOutputs();
        return switch (op) {
            case "merge" -> new StepResult(Map.of("merged", upstream));
            case "count" -> new StepResult(Map.of("count", upstream.size()));
            case "uppercase" -> new StepResult(upperCaseStrings(upstream));
            default -> throw new StepFailedException("unsupported TRANSFORM op '" + op + "'");
        };
    }

    private static Map<String, Object> upperCaseStrings(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, upperCase(value)));
        return result;
    }

    private static Object upperCase(Object value) {
        if (value instanceof String text) {
            return text.toUpperCase(Locale.ROOT);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> nested.put(String.valueOf(key), upperCase(nestedValue)));
            return nested;
        }
        return value;
    }
}
