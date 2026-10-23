package com.example.workflow.domain;

/** Resolved retry configuration for one step; defaults are applied before validation. */
public record RetryPolicy(int maxAttempts, long initialDelayMs, double multiplier, long maxDelayMs) {
}
