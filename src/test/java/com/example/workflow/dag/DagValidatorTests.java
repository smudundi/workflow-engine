package com.example.workflow.dag;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.workflow.domain.RetryPolicy;
import com.example.workflow.domain.StepSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagValidatorTests {

    private static final RetryPolicy RETRY = new RetryPolicy(3, 1000, 2.0, 30_000);

    private final DagValidator validator = new DagValidator();

    @Test
    void acceptsSequentialAndParallelDag() {
        var steps = List.of(
                step("A", "LOG", List.of()),
                step("B", "SLEEP", List.of("A")),
                step("C", "SLEEP", List.of("A")),
                step("D", "TRANSFORM", List.of("B", "C")));

        assertThat(validator.validate(steps)).isEmpty();
    }

    @Test
    void rejectsEmptyWorkflow() {
        assertThat(validator.validate(List.of()))
                .containsExactly("workflow must contain at least one step");
    }

    @Test
    void rejectsCycle() {
        var steps = List.of(
                step("A", "LOG", List.of("C")),
                step("B", "LOG", List.of("A")),
                step("C", "LOG", List.of("B")));

        assertThat(validator.validate(steps))
                .anySatisfy(msg -> assertThat(msg).contains("cycle").contains("A", "B", "C"));
    }

    @Test
    void rejectsMissingDependency() {
        var steps = List.of(step("A", "LOG", List.of("Z")));

        assertThat(validator.validate(steps))
                .containsExactly("step 'A' depends on unknown step 'Z'");
    }

    @Test
    void rejectsSelfDependency() {
        var steps = List.of(step("A", "LOG", List.of("A")));

        assertThat(validator.validate(steps))
                .containsExactly("step 'A' depends on itself");
    }

    @Test
    void rejectsDuplicateStepId() {
        var steps = List.of(step("A", "LOG", List.of()), step("A", "LOG", List.of()));

        assertThat(validator.validate(steps))
                .containsExactly("duplicate step id 'A'");
    }

    @Test
    void rejectsDuplicateDependency() {
        var steps = List.of(step("A", "LOG", List.of()), step("B", "LOG", List.of("A", "A")));

        assertThat(validator.validate(steps))
                .containsExactly("step 'B' lists dependency 'A' more than once");
    }

    @Test
    void rejectsUnknownType() {
        var steps = List.of(step("A", "HTTP", List.of()));

        assertThat(validator.validate(steps))
                .singleElement()
                .asString()
                .contains("step 'A' has unknown type 'HTTP'", "known types:");
    }

    @Test
    void rejectsMaxAttemptsOutOfBounds() {
        var steps = List.of(stepWithRetry("A", new RetryPolicy(0, 1000, 2.0, 30_000)));

        assertThat(validator.validate(steps))
                .containsExactly("step 'A' maxAttempts must be between 1 and " + DagValidator.MAX_ATTEMPTS_LIMIT);
    }

    @Test
    void rejectsMultiplierBelowOne() {
        var steps = List.of(stepWithRetry("A", new RetryPolicy(3, 1000, 0.5, 30_000)));

        assertThat(validator.validate(steps))
                .containsExactly("step 'A' multiplier must be between 1.0 and " + DagValidator.MAX_MULTIPLIER);
    }

    @Test
    void rejectsMaxDelayBelowInitialDelay() {
        var steps = List.of(stepWithRetry("A", new RetryPolicy(3, 5000, 2.0, 1000)));

        assertThat(validator.validate(steps))
                .singleElement()
                .asString()
                .startsWith("step 'A' maxDelayMs must be between initialDelayMs");
    }

    @Test
    void reportsAllProblemsAtOnce() {
        var steps = List.of(
                step("A", "NOPE", List.of("A")),
                step("A", "LOG", List.of()));

        assertThat(validator.validate(steps)).hasSize(3);
    }

    private static StepSpec step(String id, String type, List<String> dependsOn) {
        return new StepSpec(id, type, Map.of(), dependsOn, RETRY);
    }

    private static StepSpec stepWithRetry(String id, RetryPolicy retry) {
        return new StepSpec(id, "LOG", Map.of(), List.of(), retry);
    }
}
