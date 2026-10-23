package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.workflow.domain.StepFailedException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SleepStepHandlerTests {

    private final SleepStepHandler handler = new SleepStepHandler();

    @Test
    void declaresSleepType() {
        assertThat(handler.type()).isEqualTo("SLEEP");
    }

    @Test
    void sleepsAtLeastTheRequestedDuration() {
        long start = System.nanoTime();

        StepResult result = handler.execute(context(Map.of("durationMs", 100)));

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(90);
        assertThat(result.output()).containsEntry("durationMs", 100L);
    }

    @Test
    void zeroDurationReturnsImmediately() {
        StepResult result = handler.execute(context(Map.of("durationMs", 0)));

        assertThat(result.output()).containsEntry("durationMs", 0L);
    }

    @Test
    void rejectsMissingDuration() {
        assertThatThrownBy(() -> handler.execute(context(Map.of())))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("durationMs");
    }

    @Test
    void rejectsNegativeDuration() {
        assertThatThrownBy(() -> handler.execute(context(Map.of("durationMs", -1))))
                .isInstanceOf(StepFailedException.class)
                .hasMessageContaining("durationMs");
    }

    @Test
    void interruptionFailsStepAndRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> handler.execute(context(Map.of("durationMs", 10_000))))
                    .isInstanceOf(StepFailedException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static StepContext context(Map<String, Object> input) {
        return new StepContext("step-1", 1, input, Map.of());
    }
}
