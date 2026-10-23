package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlakyStepHandlerTests {

    private final FlakyStepHandler handler = new FlakyStepHandler();

    @Test
    void declaresFlakyType() {
        assertThat(handler.type()).isEqualTo("FLAKY");
    }

    @Test
    void failsWhileTotalAttemptsAreBelowThreshold() {
        assertThatThrownBy(() -> handler.execute(context(1, 2, Map.of("failUntilAttempt", 3))))
                .isInstanceOf(StepFailedException.class)
                .hasMessage("transient failure on total attempt 2");
    }

    @Test
    void succeedsOnceTotalAttemptsReachThreshold() {
        StepResult result = handler.execute(context(1, 3, Map.of("failUntilAttempt", 3)));

        assertThat(result.output()).containsEntry("totalAttempts", 3);
    }

    @Test
    void countsTotalAttemptsNotAttemptNumberSoManualRetryCanSucceed() {
        StepResult result = handler.execute(context(1, 4, Map.of("failUntilAttempt", 4)));

        assertThat(result.output()).containsEntry("totalAttempts", 4);
    }

    @Test
    void defaultThresholdFailsFirstAttemptOnly() {
        assertThatThrownBy(() -> handler.execute(context(1, 1, Map.of())))
                .isInstanceOf(StepFailedException.class);
        assertThat(handler.execute(context(2, 2, Map.of())).output()).containsEntry("totalAttempts", 2);
    }

    private static StepContext context(int attempt, int totalAttempts, Map<String, Object> input) {
        return new StepContext("step-1", attempt, totalAttempts, input, Map.of());
    }
}
