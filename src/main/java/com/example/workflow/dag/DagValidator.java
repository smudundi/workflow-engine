package com.example.workflow.dag;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import com.example.workflow.handler.HandlerRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DagValidator {

    static final int MAX_ATTEMPTS_LIMIT = 20;
    static final long MAX_DELAY_LIMIT_MS = 3_600_000L;
    static final double MAX_MULTIPLIER = 10.0;

    private final HandlerRegistry handlers;

    public DagValidator(HandlerRegistry handlers) {
        this.handlers = handlers;
    }

    /** Returns all problems found; an empty list means the definition is valid. */
    public List<String> validate(List<StepSpec> steps) {
        List<String> errors = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            errors.add("workflow must contain at least one step");
            return errors;
        }

        Set<String> ids = new HashSet<>();
        for (StepSpec step : steps) {
            if (!ids.add(step.id())) {
                errors.add("duplicate step id '" + step.id() + "'");
            }
        }

        for (StepSpec step : steps) {
            if (!handlers.supports(step.type())) {
                errors.add("step '" + step.id() + "' has unknown type '" + step.type()
                        + "'; known types: " + String.join(", ", handlers.types()));
            }
            validateRetry(step, errors);
            validateDependencies(step, ids, errors);
        }

        detectCycle(steps, ids, errors);
        return errors;
    }

    private void validateRetry(StepSpec step, List<String> errors) {
        RetryPolicy retry = step.retry();
        if (retry.maxAttempts() < 1 || retry.maxAttempts() > MAX_ATTEMPTS_LIMIT) {
            errors.add("step '" + step.id() + "' maxAttempts must be between 1 and " + MAX_ATTEMPTS_LIMIT);
        }
        if (retry.initialDelayMs() < 0 || retry.initialDelayMs() > MAX_DELAY_LIMIT_MS) {
            errors.add("step '" + step.id() + "' initialDelayMs must be between 0 and " + MAX_DELAY_LIMIT_MS);
        }
        if (!(retry.multiplier() >= 1.0 && retry.multiplier() <= MAX_MULTIPLIER)) {
            errors.add("step '" + step.id() + "' multiplier must be between 1.0 and " + MAX_MULTIPLIER);
        }
        if (retry.maxDelayMs() < retry.initialDelayMs() || retry.maxDelayMs() > MAX_DELAY_LIMIT_MS) {
            errors.add("step '" + step.id() + "' maxDelayMs must be between initialDelayMs and " + MAX_DELAY_LIMIT_MS);
        }
    }

    private void validateDependencies(StepSpec step, Set<String> ids, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String dep : step.dependsOn()) {
            if (dep.equals(step.id())) {
                errors.add("step '" + step.id() + "' depends on itself");
            } else if (!ids.contains(dep)) {
                errors.add("step '" + step.id() + "' depends on unknown step '" + dep + "'");
            } else if (!seen.add(dep)) {
                errors.add("step '" + step.id() + "' lists dependency '" + dep + "' more than once");
            }
        }
    }

    // Kahn's algorithm: steps left unprocessed are on or downstream of a cycle.
    private void detectCycle(List<StepSpec> steps, Set<String> ids, List<String> errors) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> downstream = new HashMap<>();
        for (StepSpec step : steps) {
            inDegree.putIfAbsent(step.id(), 0);
        }
        for (StepSpec step : steps) {
            for (String dep : step.dependsOn()) {
                if (dep.equals(step.id()) || !ids.contains(dep)) {
                    continue;
                }
                inDegree.merge(step.id(), 1, Integer::sum);
                downstream.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.id());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            processed++;
            for (String next : downstream.getOrDefault(id, List.of())) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        if (processed < inDegree.size()) {
            List<String> involved = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            errors.add("workflow contains a dependency cycle involving steps " + involved);
        }
    }
}
