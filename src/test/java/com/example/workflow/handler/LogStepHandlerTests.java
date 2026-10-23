package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogStepHandlerTests {

    private final LogStepHandler handler = new LogStepHandler();

    @Test
    void declaresLogType() {
        assertThat(handler.type()).isEqualTo("LOG");
    }

    @Test
    void returnsMessageAsOutput() {
        StepResult result = handler.execute(context(Map.of("message", "hello")));

        assertThat(result.output()).containsEntry("message", "hello");
    }

    @Test
    void rejectsMissingMessage() {
        assertThatThrownBy(() -> handler.execute(context(Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("message");
    }

    @Test
    void rejectsNonStringMessage() {
        assertThatThrownBy(() -> handler.execute(context(Map.of("message", 42))))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("message");
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext("step-1", 1, input, Map.of());
    }
}
