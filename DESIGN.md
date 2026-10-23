# Design: Distributed Workflow Engine

This document records the architecture decisions, alternatives, trade-offs, failure modes, scalability limits, and future improvements for the engine described in [README.md](README.md) and planned in [PLAN.md](PLAN.md).

## 1. Assumptions

The requirements leave some points open. These are the decisions made, so the behavior is predictable.

| # | Topic | Assumption |
|---|---|---|
| A1 | Workflow definitions | The full DAG is submitted inline as JSON with each request and stored, unchanged, with the workflow instance. No template registry or versioning |
| A2 | Step behavior | Steps run built-in simulated handlers (`LOG`, `SLEEP`, `HTTP`, `FLAKY`, `TRANSFORM`, `FAIL`) behind a `StepHandler` interface. The engine, not business logic, is the subject |
| A3 | Execution guarantee | At-least-once per step. Handlers get an idempotency key (`stepId:attempt`) and should be idempotent |
| A4 | Network partitions | Means a worker or the API losing connectivity to PostgreSQL. Components never talk to each other directly. Single region |
| A5 | Downstream of a failed step | Steps stay `PENDING`; no extra `SKIPPED` status, to keep the required status sets |
| A6 | In-flight siblings when a workflow fails | They finish; results are stored but do not change the workflow status |
| A7 | Manual retry | Only `FAILED` steps are reset (attempt counter to 0); `COMPLETED` steps are not re-run; attempt history is kept |
| A8 | Scale | Thousands of workflows, tens of steps each, small (KB) inputs/outputs, a handful of workers |
| A9 | Security | No authentication; trusted local network |
| A10 | Time | Lease and schedule times use database `now()`, never container clocks |
| A11 | Retention | Workflows are kept indefinitely |
| A12 | Coordinator | No dedicated coordinator process. Coordination happens through PostgreSQL transactions; the API is stateless |

## 2. Architecture Decisions

### D1. PostgreSQL as state store and work queue

Workers claim steps directly from the `step` table. A state change and the hand-off of the next unit of work happen in **one ACID transaction**, so there is no dual-write problem (e.g. "row updated but message not published"). The requirements already mandate Postgres and discourage extra dependencies; nothing in them needs a broker.

### D2. `FOR UPDATE SKIP LOCKED` for claiming

```sql
UPDATE step SET status='RUNNING', attempt=attempt+1, lease_owner=:worker,
       lease_expires_at=now()+:lease
WHERE id IN (
  SELECT s.id FROM step s JOIN workflow w ON w.id = s.workflow_id
  WHERE s.status IN ('PENDING','RETRYING') AND s.remaining_deps = 0
    AND s.next_run_at <= now() AND w.status IN ('PENDING','RUNNING')
  ORDER BY s.next_run_at LIMIT :freeSlots
  FOR UPDATE OF s SKIP LOCKED)
RETURNING *;
```

Competing workers skip rows another transaction has locked instead of blocking, so each ready step goes to exactly one worker, without contention. Row locks last only for this short transaction, never while a step runs.

### D3. Leases, heartbeats, and fencing instead of long-held locks

Holding a database lock for a step's whole run would pin connections and break on any disconnect. Instead:

- **Lease:** `lease_owner` and `lease_expires_at` (database time) mark ownership.
- **Heartbeat:** every `lease/3` the worker extends its lease with a conditional update. If the update affects 0 rows, the worker has lost the lease, interrupts the handler, and drops the result.
- **Fencing:** completion and failure updates require `lease_owner = ? AND attempt = ? AND status = 'RUNNING'`. A worker that paused, crashed, or was partitioned can never overwrite newer state.
- **Reaper:** every node periodically finds `RUNNING` steps with expired leases, marks the attempt `TIMED_OUT`, and applies the normal retry rules. It also uses `SKIP LOCKED`, so many reapers can run safely.

### D4. Dependency counter for DAG readiness

Each step stores `remaining_deps`. When a step completes, the same transaction decrements its dependents. The claim query only needs `remaining_deps = 0`, which is backed by a partial index. There is no scheduler process to scan the graph, and parallel branches become ready automatically.

### D5. Workflow finalization under a row lock

When a step completes, the transaction locks the workflow row (`FOR UPDATE`) before checking whether all steps are complete. This serializes two parallel steps that finish at the same moment, so exactly one of them marks the workflow `COMPLETED`.

### D6. Retry with capped exponential backoff and jitter

`delay = min(maxDelay, initialDelay × multiplier^(attempt−1)) ± 20%`, stored as `next_run_at`. Settings are per step with global defaults. Jitter keeps many failing steps from retrying in lockstep. Retries are just rescheduled rows, so they survive restarts.

### D7. One jar, three roles

`ENGINE_ROLE = api | worker | all`. One build, one image, and one test setup, while API and workers still scale independently in Docker Compose. `all` keeps local development and integration tests simple. Flyway runs only on API/all nodes; Swagger is disabled on workers.

### D8. Spring JDBC instead of JPA

The engine's correctness lives in a handful of precise statements (`SKIP LOCKED`, `RETURNING`, fenced conditional updates). Spring JDBC (`JdbcClient`) keeps them explicit and reviewable and avoids ORM side effects such as implicit flushes or optimistic-lock exceptions in the hot path.

### D9. Inline DAG definitions, validated at submit time

Submissions are checked for a non-empty DAG, unique step ids, existing dependencies, no self-dependencies, no cycles (Kahn's algorithm), known step types, and valid retry settings. Invalid definitions never reach the database, and workers can assume a valid graph.

### D10. Polling instead of push notifications

Workers poll every 500 ms (configurable) and claim only as many steps as they have free threads. Polling is stateless and survives reconnects with no extra logic. Its cost (latency and idle queries) is acceptable at the target scale.

### D11. Step execution history as an append-only table

`step_attempt` records every attempt with worker id, status, error, and timestamps. Manual retries and reaped attempts add rows; nothing is overwritten. This supports debugging ("which worker ran it, why did it fail") and auditing.

## 3. CAP Theorem Position

The system is **CP**.

- There is one authoritative PostgreSQL primary. A component cut off from it stops making progress rather than acting on stale state:
  - A partitioned worker cannot claim or commit. Once its lease expires, another worker takes over, and fencing rejects the original worker's late writes.
  - The API reports unhealthy and returns errors rather than stale or divergent data.
- **Why:** a workflow engine is only useful if its state is right: no duplicate completions, no lost steps, correct ordering. Short unavailability is acceptable; inconsistent state is not.
- **Where consistency is weaker, by design:** side effects of step handlers on outside systems. A partitioned worker may still finish an external call after losing its lease, and the step will run again elsewhere. That is the at-least-once guarantee (A3), mitigated by idempotency keys.
- **PACELC:** without partitions the design also favors consistency over latency: synchronous transactions, plus up to one poll interval of scheduling delay per step.
- **Clocks:** expiry uses database time only, so container clock skew cannot create two owners. A worker paused longer than its lease is exactly the case fencing handles.

## 4. Alternatives Considered

| Alternative | Why not chosen |
|---|---|
| Message broker (Kafka, RabbitMQ, SQS) | Adds infrastructure and a dual-write problem between broker and database (needs an outbox). Delayed retries and per-step leases are awkward on Kafka. The requirements discourage extra dependencies without a concrete need |
| Existing engines (Temporal, Camunda, Airflow) | Would satisfy the functional requirements but defeats the purpose of building and explaining the engine; heavy operational footprint |
| PostgreSQL `LISTEN/NOTIFY` for wakeups | Lowers latency but needs dedicated long-lived connections, reconnect handling, and polling as a fallback anyway (notifications are lost while disconnected). Kept as a future improvement |
| PostgreSQL advisory locks | Session-scoped locks are lost or leaked with connection pools and don't express expiry; row-level `SKIP LOCKED` + leases is simpler and visible in the table |
| Central scheduler that pushes work to workers | Needs leader election, worker registry, and push RPC; becomes a single point of failure. Pull-based claiming spreads load naturally |
| Holding a row lock for the whole step execution | Pins a connection per running step, and every disconnect aborts the step; long transactions block vacuum |
| JPA with optimistic locking (`@Version`) | Competing workers would collide and retry instead of skipping; hides the critical SQL |
| Event sourcing of workflow state | Strong audit trail but far more code (projections, replay) than the requirements justify; `step_attempt` gives enough history |
| Separate Maven modules / images for API and worker | Cleaner boundaries but more build and deployment overhead with little benefit at this size |
| Workflow template registry with versioning | Useful for reuse, but adds lifecycle and compatibility rules not required for submit/query/retry |

## 5. Trade-offs

| Optimized for | Sacrificed | Why acceptable |
|---|---|---|
| Correctness: transactional transitions, fencing | Availability while PostgreSQL is unreachable | Wrong workflow state is worse than a pause |
| Simplicity: PostgreSQL only | Throughput ceiling of one database; polling queries | Limits are far above the target scale; scaling path documented |
| Simple, robust polling | Up to ~500 ms scheduling latency per step | Configurable; `LISTEN/NOTIFY` can be added later |
| At-least-once execution | Exactly-once side effects | Not achievable for arbitrary external calls; idempotency keys provided |
| Leases + reaper | Crash recovery waits for lease expiry (30 s default) | Shorter leases risk false takeovers; heartbeats let long steps run with moderate leases |
| Inline definitions | No reuse or versioning of workflow definitions | Definition stored per instance, so each run is still auditable |
| One jar with roles | Less isolation between API and worker code | One artifact to build, test, deploy; roles still scale independently |
| Explicit SQL (Spring JDBC) | More hand-written SQL | The SQL is the core logic and should be visible |
| Required status sets only | No `SKIPPED`/`CANCELLED` | Matches the requirements; downstream steps simply stay `PENDING` |

## 6. Failure Modes

| Scenario | Detection | Handling | Outcome |
|---|---|---|---|
| Two workers want the same step | – | `SKIP LOCKED` claim; the second worker skips the row | Step claimed exactly once |
| Worker crashes mid-step | Heartbeats stop; lease expires | Reaper marks attempt `TIMED_OUT`; step goes to `RETRYING` (backoff) or `FAILED` | Another worker re-runs the step |
| Worker partitioned from DB | Heartbeat fails on the worker; lease expires in DB | Same as crash; when the worker reconnects, its fenced update matches 0 rows | Late result discarded; no double completion |
| Worker paused (GC, CPU starvation) past lease | Heartbeat update returns 0 rows | Worker interrupts the handler and drops the result | Same as partition |
| Connection lost while committing | Transaction error | Transaction either committed or rolled back; if rolled back, lease expires and step is retried | At-least-once |
| Step handler throws or times out | Exception / HTTP timeout | Attempt `FAILED` with error; `RETRYING` with backoff or `FAILED` if attempts exhausted | Error visible in attempt history |
| Step exhausts retries | `attempt = max_attempts` | Step `FAILED`, workflow `FAILED` with error; claim query ignores the workflow's other steps | Manual retry possible |
| Parallel steps finish simultaneously | – | Workflow row locked during finalization | Workflow completed exactly once |
| Duplicate or concurrent manual retry | – | Conditional update on `status = 'FAILED'` | One succeeds, others get `409` |
| Invalid workflow submitted | Submit-time validation | `400` with problem details; nothing stored | No bad DAG in the system |
| API container restarts | Health check | Stateless; workers keep executing | Queries resume when API is back |
| All workers down | No claims; metrics flat | Steps wait in `PENDING`/`RETRYING`; running leases expire and are reaped on restart | Work resumes when workers return |
| PostgreSQL restart / outage | Health `DOWN`, connection errors | Workers log and back off; connection pool reconnects; expired leases reaped afterwards | No data loss (durable state); progress pauses |
| Worker shutdown (deploy, scale-in) | `SIGTERM` | Stop claiming, drain in-flight steps within timeout; leftovers reaped | No lost steps |
| Poison step (always fails) | Repeated failures | Bounded by `max_attempts`, capped backoff | Workflow `FAILED`, no infinite loop |

## 7. Scalability Considerations

### Current limits

- **Single PostgreSQL primary** handles all claims, heartbeats, and API reads. Every worker polls every 500 ms; claims, heartbeats, and completions are short write transactions.
- **Claim index:** the partial index on ready steps stays small as long as few steps are ready at once; heavy backlogs make claim queries scan more rows.
- **Table growth:** `step` and `step_attempt` grow without bound (no retention).
- **Polling overhead:** idle workers still query; with many workers this becomes noticeable load.
- **Recovery latency:** bounded by the lease duration plus the reaper interval. Observed in the crash demo: the `TIMED_OUT` attempt appeared about 34 s after the step started, against a 30 s lease.

At the target scale (A8) a single Postgres instance with a few workers comfortably handles this.

### Scaling beyond

In rough order of effort:

1. **More workers / threads.** Claiming is contention-free, so throughput grows until the database is the bottleneck.
2. **Batch claims and adaptive polling.** Claim several steps per query (already supported) and back off polling when idle.
3. **`LISTEN/NOTIFY` wakeups.** Notify on newly ready steps to cut latency and idle polling.
4. **Read replicas for API queries.** Keep claims and transitions on the primary.
5. **Retention and partitioning.** Archive or drop completed workflows; partition `step_attempt` by time.
6. **Shard by workflow id.** Several Postgres instances, each owning a slice of workflows, with workers assigned per shard.
7. **Move the queue out of Postgres** (e.g. a broker plus an outbox) only when database write throughput is proven to be the limit.

## 8. Operational Considerations

- **Monitoring:** Actuator health (includes database), Micrometer counters for claims, completions, failures, retries, and reaped leases.
- **Debugging:** logs carry `workerId`, `workflowId`, `stepId` (MDC); the API exposes full attempt history with worker id and error per attempt.
- **Deployment:** one image; role via `ENGINE_ROLE`; Flyway migrations run on API start; Compose health checks order startup (postgres → api → workers); graceful shutdown drains workers.
- **Configuration:** poll interval (`engine.worker.poll-interval-ms`), thread count, lease, shutdown timeout, and retry defaults are properties. The reaper interval is fixed at 5 s.

## 9. Future Improvements (prioritized)

1. **Per-step execution timeouts.** Fail attempts that run too long even when heartbeats continue.
2. **Workflow cancellation.** `CANCELLED` state, stop claiming, interrupt in-flight steps through the lease.
3. **`LISTEN/NOTIFY` wakeups.** Lower latency and less idle polling.
4. **Retention and archiving.** Configurable cleanup of completed workflows and attempt history.
5. **Authentication and authorization.** API keys or OAuth2 on the API.
6. **Dashboards and alerts.** Prometheus endpoint with Grafana dashboards; alerts on failure rate, backlog size, reaped leases.
7. **Workflow templates and versioning.** Register definitions once, start instances by name and version.
8. **Explicit `SKIPPED` state and pause/resume.** Clearer status for steps that will not run.
9. **Pluggable handlers from outside the jar.** External handler services (HTTP/gRPC) so new step types need no redeploy.
10. **Conditional branches and sub-workflows.** Richer workflow semantics beyond static DAGs.
11. **High availability for PostgreSQL.** Managed or Patroni-based failover; tested failover behavior.
