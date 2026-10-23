package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransformStepHandlerTests {

    private final TransformStepHandler handler = new TransformStepHandler();

    @Test
    void declaresTransformType() {
        assertThat(handler.type()).isEqualTo("TRANSFORM");
    }

    @Test
    void mergeCollectsUpstreamOutputsByStepId() {
        Map<String, Object> upstream = Map.of("B", Map.of("x", 1), "C", Map.of("y", 2));

        StepResult result = handler.execute(context("merge", upstream));

        assertThat(result.output()).containsEntry("merged", upstream);
    }

    @Test
    void countReturnsNumberOfUpstreamOutputs() {
        StepResult result = handler.execute(context("count", Map.of("B", Map.of(), "C", Map.of())));

        assertThat(result.output()).containsEntry("count", 2);
    }

    @Test
    void uppercaseUpperCasesStringsIncludingNestedOnes() {
        Map<String, Object> upstream = Map.of("A", Map.of("message", "raw data", "rows", 3));

        StepResult result = handler.execute(context("uppercase", upstream));

        assertThat(result.output()).isEqualTo(Map.of("A", Map.of("message", "RAW DATA", "rows", 3)));
    }

    @Test
    void rejectsMissingOp() {
        assertThatThrownBy(() -> handler.execute(context(null, Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("op");
    }

    @Test
    void rejectsUnsupportedOp() {
        assertThatThrownBy(() -> handler.execute(context("reverse", Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("reverse");
    }

    private static StepContext context(String op, Map<String, Object> upstream) {
        Map<String, Object> input = op == null ? Map.of() : Map.of("op", op);
        return new StepContext("step-1", 1, input, upstream);
    }
}
