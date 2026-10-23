package com.example.workflow.worker;

import com.example.workflow.config.EngineProperties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(WorkerConfiguration.WORKER_ENABLED)
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    private final StepClaimer claimer;
    private final StepExecutor executor;
    private final ExecutorService pool;
    private final int threads;
    private final long leaseMs;
    private final String workerId = WorkerIds.generate();
    // Steps claimed but not yet finished; caps the claim size on each poll.
    private final AtomicInteger inFlight = new AtomicInteger();

    public WorkerLoop(StepClaimer claimer, StepExecutor executor,
                      @Qualifier("stepExecutorService") ExecutorService pool, EngineProperties properties) {
        this.claimer = claimer;
        this.executor = executor;
        this.pool = pool;
        this.threads = properties.worker().threads();
        this.leaseMs = properties.worker().lease().toMillis();
    }

    /** Claims only as many steps as there are free threads, so claimed steps never queue. */
    @Scheduled(fixedDelayString = "${engine.worker.poll-interval-ms:500}")
    public void poll() {
        int free = threads - inFlight.get();
        if (free <= 0) {
            return;
        }
        List<ClaimedStep> claimed = claimer.claim(workerId, free, leaseMs);
        for (ClaimedStep step : claimed) {
            inFlight.incrementAndGet();
            pool.execute(() -> runClaimed(step));
        }
    }

    private void runClaimed(ClaimedStep step) {
        try {
            executor.run(step, workerId);
        } catch (RuntimeException e) {
            log.error("unexpected error while running step {}", step.id(), e);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
