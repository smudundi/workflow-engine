package com.example.workflow.worker;

import com.example.workflow.config.EngineProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private final long shutdownTimeoutMs;
    private final String workerId = WorkerIds.generate();
    // Steps claimed but not yet finished; caps the claim size on each poll.
    private final AtomicInteger inFlight = new AtomicInteger();
    // Guarded by this; cleared under the same lock that poll() claims with.
    private boolean accepting = true;

    public WorkerLoop(StepClaimer claimer, StepExecutor executor,
                      @Qualifier("stepExecutorService") ExecutorService pool, EngineProperties properties) {
        this.claimer = claimer;
        this.executor = executor;
        this.pool = pool;
        this.threads = properties.worker().threads();
        this.leaseMs = properties.worker().lease().toMillis();
        this.shutdownTimeoutMs = properties.worker().shutdownTimeout().toMillis();
    }

    /** Claims only as many steps as there are free threads, so claimed steps never queue. */
    @Scheduled(fixedDelayString = "${engine.worker.poll-interval-ms:500}")
    public synchronized void poll() {
        if (!accepting) {
            return;
        }
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

    /** Stops claiming, then waits for in-flight steps; any left are re-queued by the lease reaper. */
    @PreDestroy
    public void stop() throws InterruptedException {
        synchronized (this) {
            accepting = false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMs);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        int left = inFlight.get();
        if (left == 0) {
            log.info("worker stopped; no steps in flight");
        } else {
            log.warn("shutdown timeout reached with {} step(s) in flight; leases will expire and be reaped", left);
        }
    }

    private void runClaimed(ClaimedStep step) {
        MDC.put("workerId", workerId);
        MDC.put("workflowId", step.workflowId().toString());
        MDC.put("stepId", step.stepKey());
        try {
            log.info("started attempt {}", step.attempt());
            executor.run(step, workerId, leaseMs);
        } catch (RuntimeException e) {
            log.error("unexpected error while running step {}", step.id(), e);
        } finally {
            MDC.clear();
            inFlight.decrementAndGet();
        }
    }
}
