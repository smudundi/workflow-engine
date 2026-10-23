package com.example.workflow.worker;

import com.example.workflow.config.EngineProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Worker runs for roles other than api, unless engine.worker.enabled is false. */
@Configuration
@EnableScheduling
@ConditionalOnExpression(WorkerConfiguration.WORKER_ENABLED)
public class WorkerConfiguration {

    // Must be a constant expression to be used in @ConditionalOnExpression.
    static final String WORKER_ENABLED =
            "'${engine.role:all}' != 'api' and '${engine.worker.enabled:true}' == 'true'";

    @Bean(destroyMethod = "shutdown")
    ExecutorService stepExecutorService(EngineProperties properties) {
        return Executors.newFixedThreadPool(properties.worker().threads());
    }
}
