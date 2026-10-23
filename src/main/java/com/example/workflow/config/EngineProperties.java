package com.example.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "engine")
public record EngineProperties(@DefaultValue Retry retry) {

    public record Retry(
            @DefaultValue("3") int defaultMaxAttempts,
            @DefaultValue("1s") Duration defaultInitialDelay,
            @DefaultValue("2.0") double defaultMultiplier,
            @DefaultValue("30s") Duration defaultMaxDelay) {
    }
}
