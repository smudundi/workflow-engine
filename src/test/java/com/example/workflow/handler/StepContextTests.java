package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StepContextTests {

    @Test
    void idempotencyKeyCombinesStepIdAndAttempt() {
        StepContext context = new StepContext("abc", 2, Map.of(), Map.of());

        assertThat(context.idempotencyKey()).isEqualTo("abc:2");
    }
}
