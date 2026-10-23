package com.example.workflow.domain;

/** Exponential retry delay, capped at the policy maximum, with ±20% jitter. */
public final class BackoffPolicy {

    private BackoffPolicy() {
    }

    /** Delay after failed attempt {@code attempt}; {@code unit} is uniform in [0, 1). */
    public static long delayMs(RetryPolicy policy, int attempt, double unit) {
        double base = policy.initialDelayMs() * Math.pow(policy.multiplier(), attempt - 1);
        double jitter = 0.8 + 0.4 * unit;
        long jittered = Math.round(Math.min(base, policy.maxDelayMs()) * jitter);
        return Math.min(policy.maxDelayMs(), jittered);
    }
}
