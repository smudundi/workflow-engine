# Changelog

Tracks incremental implementation progress against [PLAN.md](PLAN.md). Newest entries first.

## 2026-10-23: US3.6 Documentation

- Merged the detailed README into the root `README.md`, with the configuration table corrected to the real `engine.worker.*` properties and crash timing corrected to 20s.
- `README.md`: Quick Start (Compose and local run), Architecture Overview with a Mermaid diagram, Example Usage (curl and Swagger UI), and Testing Instructions (`mvn verify`, demo script, crash demo).
- `DESIGN.md`: corrected the configuration note. The reaper interval is fixed at 5s, not a property. The poll interval is `engine.worker.poll-interval-ms`.
- Docs-only change; no build run.

## 2026-10-23: US3.5 End-to-end tests

- Added `WorkflowEndToEndTests` (Testcontainers, 7 tests). The built-in worker is disabled, and each test starts its own in-process workers with a 2s lease.
- Covers sequential dependency order, parallel execution, backoff retry, retry exhaustion that fails the workflow, manual retry, lease-expiry recovery by another worker (simulated crash with no heartbeat), and resuming a persisted workflow after a worker restart (simulated in-process).
- Fixed a bug the tests found: `StepCompletion.finishAttempt` updated `step_attempt` by `attempt_no` alone. After a manual retry, attempt numbers restart at 1, so the new result overwrote the earlier `FAILED` row. The update now matches only the `RUNNING` attempt, consistent with `LeaseReaper`.
- Full `mvn -B clean verify` green with 141 tests.

## 2026-10-23: US3.4 Graceful worker shutdown

- `WorkerLoop.stop()` (`@PreDestroy`) stops claiming new steps, then waits up to `engine.worker.shutdown-timeout` (default 20s) for in-flight steps to finish.
- Steps still running at the timeout keep their leases, and the lease reaper re-queues them after expiry.
- `docker-compose.yml`: `engine-worker` has `stop_grace_period: 30s` so the drain completes before Docker sends SIGKILL (Docker's default is 10s).
- Added `WorkerShutdownTests` (Testcontainers, 1 test): a stopped worker finishes its running step and claims nothing new.
- Full `mvn -B clean verify` green with 134 tests.
- Manual check on the Compose stack: `docker compose stop engine-worker` during a 20s SLEEP step (`samples/crash.json`). The worker drained the step, logged "worker stopped; no steps in flight", and the workflow completed. Stop took about 16.6s.

## 2026-10-23: US3.3 Observability

- Added Micrometer counters, exposed through `/actuator/metrics/workflow.steps.<name>`:
  - `claimed`: steps claimed by `StepClaimer`.
  - `completed`: steps whose attempt succeeded.
  - `retried`: failed attempts rescheduled for retry.
  - `failed`: steps that exhausted their attempts.
  - `reaped`: steps recovered from expired leases by `LeaseReaper`.
- Worker log lines carry MDC keys `workerId`, `workflowId`, and `stepId` (the step key, for example `A`). `WorkerLoop` sets them around each step and clears them afterwards. `application.yml` sets `logging.pattern.level` to print them in brackets.
- Added `MetricsEndpointTests` (Testcontainers, 1 test): all five counters return 200 from `/actuator/metrics`.
- Full `mvn -B clean verify` green with 133 tests.

## 2026-10-23: US3.2 Sample workflows and demo script

- Added `scripts/demo.sh` (curl + jq). It waits for `/actuator/health`, submits every file in `samples/`, polls each workflow to `COMPLETED` or `FAILED`, runs a manual retry for `failing.json` when it fails, and prints a table of expected and actual states. It exits 1 if any sample misses its expected state.
- Settings: `BASE_URL` (default `http://localhost:8080`) and `TIMEOUT_SECONDS` (default 180, which covers the 60-second `crash.json`).
- Run against `docker compose up --build -d`: crash, failing (after manual retry), parallel, retry, and sequential all ended `COMPLETED`; the script exited 0.
- No Java changes in this story, so the test count is unchanged. The last full `mvn -B clean verify` (US3.1) was green with 132 tests.

## 2026-10-23: US3.1 List workflows

- Added `GET /api/workflows?status=&page=&size=`: newest first (`created_at DESC, id DESC`), optional `status` filter, `page` 0-based (default 0, minimum 0), `size` default 20 with a range of 1-100.
- Response is `WorkflowPageResponse` with `content` (summary items), `page`, `size`, and `totalElements`. Single-workflow responses are unchanged.
- `WorkflowRepository.findPage` and `count` treat a null status as no filter.
- `GlobalExceptionHandler` now maps an invalid `status` value and out-of-range `page` or `size` to 400 problem responses with an `errors` list. These are handled explicitly so the format matches the other endpoints.
- Added four `WorkflowControllerTests` cases: newest-first ordering with paging metadata, status filter, unknown status returns 400, and out-of-range paging returns 400.
- Test counts: WorkflowControllerTests 21, FlakyStepHandlerTests 5, HttpStepHandlerTests 6, FlakyAndHttpExecutionTests 2, WorkflowRetryTests 2, FailurePropagationTests 6, RetryBackoffTests 3, StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, BackoffPolicyTests 5, WorkerIdsTests 2, PersistenceRepositoryTests 16, DagValidatorTests 12, HandlerRegistryTests 4, TransformStepHandlerTests 6, FailStepHandlerTests 3, LogStepHandlerTests 4, StepContextTests 1, SleepStepHandlerTests 6, SchemaMigrationTests 3, WorkflowEngineApplicationTests 1, WorkflowSubmitRollbackTests 1. Full `mvn -B clean verify` green with 132 tests.

## 2026-10-23: US2.7 FLAKY and HTTP step handlers

- Added `FLAKY` handler: fails while `StepContext.totalAttempts` is below `failUntilAttempt` (default 2), then succeeds with `totalAttempts` in its output. Counting total attempts keeps it working after a manual retry, which resets `attempt`.
- `ClaimedStep` and `StepContext` now carry `totalAttempts`, counted from `step_attempt` rows right after each claim. `StepContext` keeps a 4-argument constructor for existing callers.
- Added `HTTP` handler using the JDK `HttpClient`: inputs `url` (required), `method` (default `GET`, no body), `timeoutMs` (default 5000). Non-2xx responses, timeouts, and connection errors are step failures.
- Changed `WorkflowControllerTests.unknownTypeReturns400` to use an unregistered type, since `HTTP` is now registered.
- Added `FlakyStepHandlerTests` (5 unit tests), `HttpStepHandlerTests` (6 unit tests, JDK `HttpServer` for success, 503, timeout, unreachable port, and missing URL), and `FlakyAndHttpExecutionTests` (Testcontainers, 2 tests): FLAKY fails until a manual retry reaches the threshold and then completes with `totalAttempts` 3; HTTP to a closed port fails the workflow after one attempt.
- Test counts: FlakyStepHandlerTests 5, HttpStepHandlerTests 6, FlakyAndHttpExecutionTests 2, WorkflowControllerTests 17, WorkflowRetryTests 2, FailurePropagationTests 6, RetryBackoffTests 3, StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, BackoffPolicyTests 5, WorkerIdsTests 2, PersistenceRepositoryTests 16, DagValidatorTests 12, HandlerRegistryTests 4, TransformStepHandlerTests 6, FailStepHandlerTests 3, LogStepHandlerTests 4, StepContextTests 1, SleepStepHandlerTests 6, SchemaMigrationTests 3, WorkflowEngineApplicationTests 1, WorkflowSubmitRollbackTests 1. Full `mvn -B clean verify` green with 128 tests.

## 2026-10-23: US2.6 Manual retry

- Added `POST /api/workflows/{id}/retry`: returns 200 with the workflow detail, 404 for an unknown workflow, 409 when the workflow is not `FAILED`.
- `WorkflowService.retry` runs in one transaction. It locks the failed steps, then the workflow row (same order as `StepCompletion`), then resets failed steps to `PENDING` with `attempt = 0` and sets the workflow to `RUNNING` with the error cleared.
- Completed steps and their `step_attempt` rows are untouched. Attempt history is kept; `attempt_no` restarts at 1 for each manual retry round.
- Added `ConflictException`, mapped to a 409 problem response by `GlobalExceptionHandler`.
- Added `WorkflowRetryTests` (Testcontainers, 2 tests): a failed workflow is retried and completes without re-running a completed sibling; retry of a non-failed workflow is rejected and changes nothing.
- Added eight tests to `WorkflowControllerTests`: retry of a failed workflow returns 200 and `RUNNING`; retry of a pending, running, or completed workflow returns 409; a second retry returns 409; retry of an unknown workflow returns 404; completed steps and attempt history are kept while the failed step is reset.
- Test counts: WorkflowControllerTests 17, WorkflowRetryTests 2, FailurePropagationTests 6, StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, RetryBackoffTests 3, BackoffPolicyTests 5, WorkerIdsTests 2. Full `mvn -B clean verify` green with 115 tests.

## 2026-10-23: US2.5 Failure propagation

- A step that exhausts its attempts is `FAILED` and its workflow is `FAILED` with error `step '<key>' failed: <error>`; this path was already in `StepCompletion.fail` and `LeaseReaper`.
- Confirmed the claim query only selects steps whose workflow is `PENDING` or `RUNNING`, so unrelated ready steps are not claimed after a workflow fails.
- Dependents of a failed step keep `remaining_deps` above zero and are never claimed.
- Added `FailurePropagationTests` (Testcontainers, 6 tests): exhausted step fails the workflow with the step error; dependent stays `PENDING` and is not claimed; an independent sibling completing after the failure leaves the workflow `FAILED`; an unrelated ready step is not claimed after failure; a failure before exhaustion keeps the workflow `RUNNING`; the last allowed attempt fails the workflow.
- Test counts: FailurePropagationTests 6, BackoffPolicyTests 5, RetryBackoffTests 3, StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, WorkerIdsTests 2. Full `mvn -B clean verify` green with 105 tests.

## 2026-10-23: US2.4 Retry with exponential backoff

- Added `BackoffPolicy`: delay is `initial * multiplier^(attempt-1)`, capped at the max delay, with ±20% jitter applied before the cap.
- `StepCompletion.fail` now sets `next_run_at` from the backoff delay instead of making a failed step immediately claimable.
- `LeaseReaper` applies the same backoff to steps it requeues as `RETRYING`.
- Added `BackoffPolicyTests` (5 unit tests): exponential growth, cap, jitter bounds, jittered cap, and zero initial delay.
- Added `RetryBackoffTests` (Testcontainers, 3 tests): a step that fails twice then succeeds passes through `RETRYING` with growing gaps between attempts; a retry is not claimable before its backoff elapses; a reaped step waits for its backoff. These use `LOG` steps driven through `StepCompletion` because the `FLAKY` step type is US2.7.
- Test counts: BackoffPolicyTests 5, RetryBackoffTests 3, StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, WorkerIdsTests 2. Full `mvn -B verify` green with 99 tests. `RetryBackoffTests` repeated 3 times, all passing.

## 2026-10-23: US2.3 Lease reaper

- Added `LeaseReaper`: in one transaction, finds `RUNNING` steps with an expired lease (`FOR UPDATE SKIP LOCKED`), marks them `RETRYING` or `FAILED` (when attempts are exhausted), and closes the open attempt as `TIMED_OUT` with error `lease expired`.
- An exhausted step fails its workflow through the shared `StepCompletion.failWorkflow`.
- Scheduled every 5 s via `@Scheduled`; it runs only where `WorkerConfiguration` enables scheduling, i.e. worker roles.
- Requeued steps are re-claimed immediately (no backoff until US2.4). A stale worker's late complete or renew is rejected by the existing fencing.
- Added `LeaseReaperTests` (Testcontainers, 3 tests): an expired lease is requeued with a `TIMED_OUT` attempt, the stale completion is rejected, and the next claim is attempt 2; an exhausted expired lease fails the step and workflow with "lease expired"; a live lease is not reaped.
- Test counts: StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, LeaseReaperTests 3, WorkerIdsTests 2. Full `mvn -B verify` green with 91 tests.

## 2026-10-23: US2.2 Leases, heartbeat, and fencing

- Added `LeaseHeartbeat`: renews a claimed step's lease every lease/3 while its handler runs. Renewal is fenced on lease owner, attempt, and `RUNNING` status.
- If a renewal matches no row, the lease is lost: the handler thread is interrupted and its result is discarded (no complete or fail).
- `StepExecutor` starts a watch before the handler, stops it afterwards, and clears any late interrupt. `WorkerLoop` passes the lease duration through.
- Removed the stale "heartbeats arrive with US2.2" comment in `StepClaimer`.
- Added `LeaseHeartbeatTests` (Testcontainers, 3 tests): renewal extends the current attempt's lease; a re-claimed step fences the old worker's renew and complete while the new worker completes; a handler sleeping 10 s is interrupted and its result discarded when the lease is stolen.
- Test counts: StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, LeaseHeartbeatTests 3, WorkerIdsTests 2. Full `mvn -B verify` green with 88 tests. `LeaseHeartbeatTests` and `WorkerExecutionTests` repeated 5 times, all passing.
- Lease expiry reaping (requeue of stale RUNNING steps) is US2.3; the steal test simulates it.

## 2026-10-23: US2.1 Multi-worker safe claiming

- Added `WorkerIds`: worker id is the hostname plus a random 8-character suffix, so replicas are distinguishable in `step_attempt.worker_id` and lease owners. `WorkerLoop` uses it.
- `docker-compose.yml` runs two `engine-worker` replicas (`deploy.replicas: 2`).
- The claim query already used `FOR UPDATE OF s SKIP LOCKED`; no change to claim SQL.
- Added `StepClaimerTests.concurrentClaimersClaimEachStepExactlyOnce`: 8 threads claim 40 ready steps; each step is claimed once and produces one attempt row.
- Added `WorkerIdsTests` (2 unit tests).
- Test counts: StepClaimerTests 6, WorkerExecutionTests 4, StepCompletionTests 7, WorkerIdsTests 2. Full `mvn -B verify` green with 85 tests. StepClaimerTests repeated 5 times, all passing.

## 2026-10-23: US1.8 Containerization

- `docker-compose.yml` runs postgres, engine-api, and one engine-worker. The worker has `replicas` removed (US2.1 adds more).
- Dockerfile is a multi-stage build (Maven build, then JRE runtime); unchanged in this increment.
- Samples: `sequential.json` and `parallel.json` are at `samples/` (repo root, verified COMPLETED). References in README, PLAN, and `WorkflowController` updated.
- Verified with `docker compose up -d --build`: API became healthy, the `sequential.json` sample submitted and reached `COMPLETED`, and worker logs had no errors.
- Fixed flaky `WorkerExecutionTests.parallelBranchesOverlapAndJoinBeforeTheMergeStep`: step and attempt timestamps in `StepClaimer` and `StepCompletion` now use `clock_timestamp()` (write time) instead of `now()` (transaction start), so a dependent's `started_at` cannot precede its dependency's `completed_at`. Lease and retry scheduling still use `now()`.
- No new Java tests. Unit and Testcontainers suite unchanged from US1.7 (82 tests: StepClaimerTests 5, WorkerExecutionTests 4, StepCompletionTests 7, plus others). Full `mvn -B verify` green; worker test classes repeated 5 times, all passing.

## 2026-10-23: US1.7 Worker execution loop

- Added `StepClaimer`: claims ready steps with `FOR UPDATE SKIP LOCKED`, sets lease owner and expiry, increments `attempt`, records a `step_attempt` row, and returns upstream outputs.
- Added `StepCompletion`: fenced complete and fail (lease owner, attempt, and `RUNNING` status must match). Completion decrements dependents' `remaining_deps` and finalizes the workflow under a row lock. Failure retries immediately until `maxAttempts`, then fails the step and workflow.
- Added `WorkerLoop` (`@Scheduled` poll, claims only free threads) and `StepExecutor` (runs handlers outside any transaction). Worker thread pool is sized by `engine.worker.threads`.
- Added `engine.worker` properties (`enabled`, `threads`, `lease`) and `engine.worker.poll-interval-ms`. The worker is off for role `api`.
- Existing submit tests disable the worker (`engine.worker.enabled=false`) so state assertions are stable.
- Tests: `StepClaimerTests` 5, `WorkerExecutionTests` 4 (sequential order, parallel overlap with merge, FAIL single attempt, FAIL retries then fails), `StepCompletionTests` 7 (completion, stale worker and earlier attempt fencing, double completion, retry state, exhausted failure, parallel branch after failure).
- `mvn -B verify` green: 82 tests, 0 failures, 0 errors.
- Test contexts with the worker on are closed after their class (`@DirtiesContext`), and contexts that do not need the worker disable it.

## 2026-10-23: US1.6 Step handler SPI

- Added `StepHandler` SPI, `StepContext` (idempotency key `stepId:attempt`), `StepResult`, and `HandlerRegistry` (rejects duplicate types).
- Added `LOG`, `SLEEP` (interruptible), `TRANSFORM` (`merge`, `count`, `uppercase`), and `FAIL` handlers. Each throws `StepFailedException` on bad input or failure.
- `DagValidator` now checks step types against `HandlerRegistry` instead of a hard-coded list; error text unchanged.
- Added unit tests for each handler, the registry, and `StepContext`.
- `mvn -B verify` green: 66 tests, 0 failures.

## 2026-10-23: Persistence integration tests

- Added `PersistenceRepositoryTests` (Testcontainers, Postgres 16): submit storage, `remaining_deps`, retry columns, dependency rows, step and attempt lookups, JSON output read-back, constraint rejections, and workflow delete cascade.
- Added `WorkflowSubmitRollbackTests`: a failure during dependency insert rolls back workflow and step rows.
- `mvn -B verify` green: 42 tests, 0 failures.

## 2026-10-23: US1.5 Swagger / OpenAPI

- Added `OpenApiConfig` with API title and version; springdoc serves `/v3/api-docs` and `/swagger-ui.html` on `api` and `all`.
- Added `@Tag`, `@Operation`, and `@ApiResponses` to `WorkflowController`; error responses use `application/problem+json`.
- Added the A→(B,C)→D→E example as an `@ExampleObject` on the submit request body.
- Added `@Schema` descriptions to `SubmitWorkflowRequest`.
- Added `apiDocsDescribeEndpointsAndSampleExample` to `WorkflowControllerTests`.
- `mvn -B verify` green: 25 tests, 0 failures.

## 2026-10-23: US1.4 Query workflow and step status

- Added `GET /api/workflows/{id}` returning workflow status, steps (status, attempt, maxAttempts, output, lastError, nextRunAt, timestamps) and attempt history.
- Added `GET /api/workflows/{id}/steps/{stepId}` returning one step with its attempts; `stepId` is the step key.
- Unknown workflow or step returns 404 ProblemDetail via `NotFoundException` and `GlobalExceptionHandler`.
- Added `WorkflowRow`, `StepRow`, `AttemptRow` read models and `AttemptRepository`; `WorkflowRepository.findById` and `StepRepository` read queries.
- Added GET tests to `WorkflowControllerTests`.
- `mvn -B verify` green: 24 tests, 0 failures.

## 2026-10-23: US1.3 Submit workflow with DAG validation

- Added `POST /api/workflows` returning 201 `{id, status}`; invalid definitions return 400 ProblemDetail with an `errors` list.
- Added `DagValidator`: unique ids, unknown and self dependencies, duplicate dependencies, Kahn cycle detection, known types, retry bounds.
- Added `WorkflowService.submit` (single transaction) with `WorkflowRepository` and `StepRepository` (Spring JDBC).
- Added `EngineProperties` with `engine.retry.*` defaults; `@ConfigurationPropertiesScan` on the application.
- Known types are a temporary constant until the HandlerRegistry lands in US1.6.
- Added `DagValidatorTests` (12 unit tests) and `WorkflowControllerTests` (Testcontainers API tests).
- `mvn -B verify` green: 20 tests, 0 failures.

## 2026-10-23: US1.2 Database schema

- Added `V1__init.sql`: `workflow`, `step`, `step_dependency`, `step_attempt` tables.
- Added ready-step, lease-reaper, and dependency indexes; status CHECK constraints; `UNIQUE(workflow_id, step_key)`.
- Added `application-api.yml` and `application-all.yml` (Flyway and Swagger enabled).
- `spring.profiles.active` now follows `ENGINE_ROLE`.
- Added `SchemaMigrationTests` (Testcontainers): migration applied, tables and indexes present.

## 2026-10-23: US1.1 Project scaffold (partial)

- `pom.xml` on Spring Boot 3.3.13 with web, validation, actuator, JDBC, Flyway, PostgreSQL, springdoc, Testcontainers.
- `WorkflowEngineApplication`, `application.yml`, `application-worker.yml` (Flyway and Swagger disabled).
- Profile files `application-api.yml` and `application-all.yml` were added in US1.2.
