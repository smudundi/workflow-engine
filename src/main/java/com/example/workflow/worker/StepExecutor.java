package com.example.workflow.worker;

import com.example.workflow.handler.HandlerRegistry;
import com.example.workflow.handler.StepContext;
import com.example.workflow.handler.StepHandler;
import com.example.workflow.handler.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final HandlerRegistry handlers;
    private final StepCompletion completion;
    private final LeaseHeartbeat heartbeat;

    public StepExecutor(HandlerRegistry handlers, StepCompletion completion, LeaseHeartbeat heartbeat) {
        this.handlers = handlers;
        this.completion = completion;
        this.heartbeat = heartbeat;
    }

    /** Runs the step's handler outside any transaction and records the outcome. */
    public void run(ClaimedStep step, String workerId, long leaseMs) {
        // No DB transaction is open here, so no row lock is held during handler code.
        LeaseHeartbeat.Watch lease = heartbeat.watch(step, workerId, leaseMs, Thread.currentThread());
        StepResult result = null;
        RuntimeException failure = null;
        try {
            StepHandler handler = handlers.get(step.type());
            result = handler.execute(new StepContext(step.id().toString(), step.attempt(),
                    step.input(), step.upstreamOutputs()));
        } catch (RuntimeException e) {
            failure = e;
        }
        lease.stop();
        // Clears a late interrupt so it cannot leak into the next step on this thread.
        Thread.interrupted();
        if (lease.lost()) {
            log.warn("step {} attempt {} lost its lease; result discarded", step.id(), step.attempt());
        } else if (failure != null) {
            log.warn("step {} attempt {} failed: {}", step.id(), step.attempt(), failure.getMessage());
            completion.fail(step, workerId, messageOf(failure));
        } else if (!completion.complete(step, workerId, result.output())) {
            log.warn("step {} attempt {} lost its lease; result discarded", step.id(), step.attempt());
        }
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
