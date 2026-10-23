package com.example.workflow.worker;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Keeps a claimed step's lease alive while its handler runs. */
@Component
public class LeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final JdbcClient jdbc;
    // One thread is enough: each renewal is a single primary-key UPDATE.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public LeaseHeartbeat(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when the lease was lost: the step was re-claimed or left RUNNING. */
    public boolean renew(ClaimedStep step, String workerId, long leaseMs) {
        return jdbc.sql("""
                UPDATE step SET lease_expires_at = now() + CAST(:lease AS interval)
                WHERE id = :id AND status = 'RUNNING' AND attempt = :attempt AND lease_owner = :worker
                """)
                .param("lease", leaseMs + " milliseconds")
                .param("id", step.id())
                .param("attempt", step.attempt())
                .param("worker", workerId)
                .update() == 1;
    }

    /** Renews every lease/3 until stopped; interrupts the handler thread if the lease is lost. */
    public Watch watch(ClaimedStep step, String workerId, long leaseMs, Thread handlerThread) {
        Watch watch = new Watch();
        long period = Math.max(1, leaseMs / 3);
        synchronized (watch) {
            watch.future = scheduler.scheduleAtFixedRate(
                    () -> renewOrInterrupt(watch, step, workerId, leaseMs, handlerThread),
                    period, period, TimeUnit.MILLISECONDS);
        }
        return watch;
    }

    private void renewOrInterrupt(Watch watch, ClaimedStep step, String workerId, long leaseMs,
                                  Thread handlerThread) {
        synchronized (watch) {
            if (watch.stopped) {
                return;
            }
            try {
                if (!renew(step, workerId, leaseMs)) {
                    watch.lost = true;
                    watch.stopped = true;
                    watch.future.cancel(false);
                    handlerThread.interrupt();
                }
            } catch (RuntimeException e) {
                // Retried on the next tick; if the database stays down, the lease expires and is reaped.
                log.warn("lease renewal for step {} failed: {}", step.id(), e.getMessage());
            }
        }
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }

    public static final class Watch {

        private ScheduledFuture<?> future;
        private boolean stopped;
        private boolean lost;

        /** Once this returns, no interrupt can reach the handler thread. */
        public synchronized void stop() {
            stopped = true;
            future.cancel(false);
        }

        public synchronized boolean lost() {
            return lost;
        }
    }
}
