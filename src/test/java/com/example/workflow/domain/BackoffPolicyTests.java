package com.example.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffPolicyTests {

    private static final RetryPolicy POLICY = new RetryPolicy(5, 1000, 2.0, 10_000);

    @Test
    void delayGrowsExponentiallyWithoutJitter() {
        assertThat(BackoffPolicy.delayMs(POLICY, 1, 0.5)).isEqualTo(1000);
        assertThat(BackoffPolicy.delayMs(POLICY, 2, 0.5)).isEqualTo(2000);
        assertThat(BackoffPolicy.delayMs(POLICY, 3, 0.5)).isEqualTo(4000);
    }

    @Test
    void delayIsCappedAtMaximum() {
        assertThat(BackoffPolicy.delayMs(POLICY, 4, 0.5)).isEqualTo(8000);
        assertThat(BackoffPolicy.delayMs(POLICY, 5, 0.5)).isEqualTo(10_000);
        assertThat(BackoffPolicy.delayMs(POLICY, 500, 0.99)).isEqualTo(10_000);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(BackoffPolicy.delayMs(POLICY, 1, 0.0)).isEqualTo(800);
        assertThat(BackoffPolicy.delayMs(POLICY, 1, 0.9999)).isEqualTo(1200);
        assertThat(BackoffPolicy.delayMs(POLICY, 2, 0.0)).isEqualTo(1600);
        assertThat(BackoffPolicy.delayMs(POLICY, 2, 0.9999)).isEqualTo(2400);
    }

    @Test
    void jitteredCapNeverExceedsMaximum() {
        assertThat(BackoffPolicy.delayMs(POLICY, 5, 0.999)).isEqualTo(10_000);
    }

    @Test
    void zeroInitialDelayStaysZero() {
        assertThat(BackoffPolicy.delayMs(new RetryPolicy(3, 0, 1.0, 0), 2, 0.7)).isZero();
    }
}
