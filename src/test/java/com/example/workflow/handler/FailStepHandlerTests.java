package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FailStepHandlerTests {

    private final FailStepHandler handler = new FailStepHandler();

    @Test
    void declaresFailType() {
        assertThat(handler.type()).isEqualTo("FAIL");
    }

    @Test
    void alwaysFailsWithConfiguredMessage() {
        assertThatThrownBy(() -> handler.execute(context(Map.of("message", "boom"))))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("boom");
    }

    @Test
    void usesDefaultMessageWhenNoneGiven() {
        assertThatThrownBy(() -> handler.execute(context(Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("Step failed");
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext("step-1", 1, input, Map.of());
    }
}
