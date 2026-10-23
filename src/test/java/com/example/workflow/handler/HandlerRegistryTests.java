package com.example.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandlerRegistryTests {

    private final HandlerRegistry registry = new HandlerRegistry(List.of(
            new LogStepHandler(), new SleepStepHandler(), new TransformStepHandler(), new FailStepHandler()));

    @Test
    void resolvesHandlerByType() {
        assertThat(registry.get("SLEEP")).isInstanceOf(SleepStepHandler.class);
        assertThat(registry.supports("SLEEP")).isTrue();
    }

    @Test
    void reportsUnknownTypeAsUnsupported() {
        assertThat(registry.supports("HTTP")).isFalse();
        assertThatThrownBy(() -> registry.get("HTTP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }

    @Test
    void listsTypesInSortedOrder() {
        assertThat(registry.types()).containsExactly("FAIL", "LOG", "SLEEP", "TRANSFORM");
    }

    @Test
    void rejectsDuplicateTypes() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new LogStepHandler(), new LogStepHandler())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOG");
    }
}
