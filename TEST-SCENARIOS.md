# Test Scenario Catalogue

Scope: all implemented features through US3.5 (submission, DAG validation, execution, claiming, leases, fencing, lease reaping, retry backoff, failure propagation, manual retry, handlers, list API, shutdown, metrics, end-to-end). Items that cannot be automated are listed in section 12. Known untested cases are listed in section 13.

## Method

- Standard: ISTQB Foundation test design techniques, with scenario fields modeled on the ISO/IEC/IEEE 29119-3 test design specification (ID, purpose, input, pass/fail criteria). Replaces IEEE 829-2008.
- Techniques used:
  - Equivalence partitioning (EP): valid and invalid input classes.
  - Boundary value analysis (BVA): values at and around limits.
  - State transition testing (STT): step and workflow status changes.
  - Decision table (DT): combinations of conditions and outcomes.
- Scenario types:
  - Positive: expected valid behavior.
  - Negative: invalid input or unexpected state; the system must reject or contain it.
  - Edge: values at the limit of a valid range.
  - Corner: unusual combinations or concurrency.

## Terms

- Step status: `PENDING`, `RUNNING`, `RETRYING`, `COMPLETED`, `FAILED`, `TIMED_OUT` (attempt only).
- Workflow status: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`.
- Lease: default 30 s (`engine.worker.lease`); heartbeat renews every lease/3; reaper runs every 5 s.
- Backoff: `delay = initial × multiplier^(attempt−1)`, capped at `maxDelayMs`, with ±20% jitter applied before the cap.

## Scenario Format

Each row gives an ID, the scenario type and technique, what is tested, the success criterion (pass) and the failure criterion (fail).

## 1. Workflow Submission (API)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| SUB-P01 | Positive | EP | Submit a valid sequential DAG (A → B → C) | 201 with `status` `PENDING` and a UUID `id`; `GET` returns all three steps `PENDING` | Non-201 status, missing id, or steps not persisted |
| SUB-P02 | Positive | EP | Submit a DAG with a fan-out and fan-in (A → (B, C) → D) | 201; the DAG is stored with the correct dependencies | Any dependency missing or wrong in the response |
| SUB-P03 | Positive | EP | Submit a step with no `input`, no `dependsOn`, and no `retry` | 201; defaults applied (empty input, no dependencies, configured retry defaults) | 400, or a null field in the stored step |
| SUB-N01 | Negative | EP | Submit a body that is not valid JSON | 400 with `application/problem+json` | 2xx, or a 500 response |
| SUB-N02 | Negative | EP | Submit a DAG containing a cycle (A → B → A) | 400 with `application/problem+json`; nothing persisted | 201, or a partial workflow row in the database |
| SUB-N03 | Negative | EP | Submit a step that depends on an unknown step id | 400 with `application/problem+json`; nothing persisted | 201, or a 500 response |
| SUB-N04 | Negative | EP | Submit two steps with the same id | 400 with `application/problem+json` | 201 with one step silently dropped |
| SUB-N05 | Negative | EP | `GET /api/workflows/{id}` with an unknown UUID | 404 with `application/problem+json` | 200, or a 500 response |
| SUB-N06 | Negative | EP | `GET /api/workflows/{id}/steps/{stepId}` with an unknown step key | 404 with `application/problem+json` | 200 with a different step's data |
| SUB-N07 | Negative | EP | `GET /api/workflows/not-a-uuid` | 400 (type mismatch) | 200, or a 500 response |
| SUB-E01 | Edge | BVA | Retry `maxAttempts` = 1 (lower bound) | Accepted; the first failure exhausts the step | Accepted with extra attempts, or rejected |
| SUB-E02 | Edge | BVA | Retry `maxAttempts` = 0 (below lower bound) | 400 with `application/problem+json`, or rejected at claim time with the step failed | Step runs with zero attempts allowed |
| SUB-C01 | Corner | DT | A diamond where two dependencies share a parent | 201; the join step waits for both parents | Join step eligible before both parents complete |

## 2. Claiming and Dispatch (StepClaimer)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| CLM-P01 | Positive | STT | A `PENDING` step with completed dependencies is claimed | Status becomes `RUNNING`; lease owner, expiry, and attempt 1 are set; one attempt row is created | No claim, or status not `RUNNING` |
| CLM-P02 | Positive | STT | Upstream outputs are passed to the claimed step | The claimed step's upstream map contains each dependency's output | Missing or wrong upstream outputs |
| CLM-N01 | Negative | DT | A step whose dependency is still `RUNNING` | Not claimed; remains `PENDING` | Claimed before its dependency completes |
| CLM-N02 | Negative | DT | A step whose dependency `FAILED` | Not claimed | Claimed after an upstream failure |
| CLM-N03 | Negative | STT | A step with `next_run_at` in the future (backoff not elapsed) | Not claimed until `next_run_at` passes | Claimed before `next_run_at` |
| CLM-E01 | Edge | BVA | Claim exactly at `next_run_at` | Claimed (boundary is inclusive) | Not claimed at the exact timestamp when eligible |
| CLM-C01 | Corner | Concurrency | 8 threads claim 40 ready steps at once | Each step is claimed exactly once; one attempt row per step | Any step claimed twice, or any step never claimed |
| CLM-C02 | Corner | Concurrency | Two workers race on the last ready step | Exactly one wins; the other skips (`SKIP LOCKED`) and receives no step | Both receive the step |

## 3. Execution and Completion (Fencing)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| EXE-P01 | Positive | STT | Handler succeeds; step completes | Step `COMPLETED`; attempt `SUCCEEDED`; dependents become claimable | Step not `COMPLETED`, or dependents never claimed |
| EXE-P02 | Positive | STT | The last step completes | Workflow `COMPLETED` with `completedAt` set | Workflow stays `RUNNING` |
| EXE-N01 | Negative | Fencing | Stale worker completes after its lease was taken over | Completion rejected; new owner's state unchanged | Stale completion overwrites the new owner's result |
| EXE-N02 | Negative | Fencing | Stale worker fails after its lease was taken over | Failure rejected; no retry scheduled by the stale worker | Stale failure changes the step |
| EXE-N03 | Negative | Fencing | Completion with a mismatched attempt number | Rejected | Accepted |
| EXE-N04 | Negative | STT | Completion on a step that is not `RUNNING` | Rejected; status unchanged | Status changed from `COMPLETED` or `FAILED` |
| EXE-C01 | Corner | Concurrency | Two completions for the same step race | Exactly one applies; dependents' `remaining_deps` decremented once | Dependency count decremented twice |

## 4. Leases, Heartbeat, and Reaping

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| LSE-P01 | Positive | STT | A long handler keeps its lease alive | Lease renewed every lease/3; the step is not reaped | Step reaped while the worker is alive |
| LSE-P02 | Positive | STT | Worker dies; lease expires; step is reaped | Attempt marked `TIMED_OUT` with error `lease expired`; step `RETRYING` with backoff; a surviving worker reclaims it as attempt 2 | Step stuck in `RUNNING`, or no attempt 2 |
| LSE-N01 | Negative | Fencing | Heartbeat renewal after the lease was lost | Renewal affects no rows; handler thread is interrupted and its result discarded | Handler result written after loss |
| LSE-N02 | Negative | STT | A live (not expired) lease | Not reaped | Reaped while the lease is valid |
| LSE-E01 | Edge | BVA | Lease expires exactly at the reaper's scan time | Reaped on the next scan (at most 5 s later) | Never reaped |
| LSE-E02 | Edge | BVA | Lease with `maxAttempts` already reached | Attempt `TIMED_OUT`; step `FAILED`; workflow `FAILED` with error `lease expired` | Step requeued beyond `maxAttempts` |
| LSE-C01 | Corner | Concurrency | Reaper and a live worker act on the same step at the same time | Reaper uses `SKIP LOCKED`; no double transition; end state consistent | Two attempts for one lease expiry, or inconsistent status |

## 5. Retry Backoff (BackoffPolicy)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| BKF-P01 | Positive | Unit | Delay grows exponentially with attempt (1 s, 2 s, 4 s for multiplier 2) | Each delay within ±20% of the expected value | Growth not exponential |
| BKF-P02 | Positive | Integration | Flaky step fails twice, then succeeds | Status passes through `RETRYING`; gaps between attempts grow | No `RETRYING` state, or gaps not increasing |
| BKF-N01 | Negative | Unit | Attempt number 0 or negative | Rejected or treated as attempt 1; never a negative delay | Negative or zero-length delay produced |
| BKF-N02 | Negative | Integration | Retry claimed before `next_run_at` | Not claimed | Claimed early |
| BKF-E01 | Edge | BVA | Jitter at unit 0.0 and unit 0.9999 | Delay within 0.8× and 1.2× of the base value | Outside the ±20% band |
| BKF-E02 | Edge | BVA | Delay reaches `maxDelayMs` | Capped at `maxDelayMs`, even with maximum jitter | Exceeds `maxDelayMs` |
| BKF-E03 | Edge | BVA | `initialDelayMs` = 0 | Delay 0; step claimable immediately | Negative or error |
| BKF-C01 | Corner | Unit | Very large attempt number (e.g., 500) | Result equals `maxDelayMs`; no overflow | Overflow, NaN, or delay below cap |

## 6. End-to-End (Sample Workflows)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| E2E-P01 | Positive | Scenario | `samples/sequential.json` | Workflow `COMPLETED` | Not `COMPLETED` |
| E2E-P02 | Positive | Scenario | `samples/parallel.json` | `COMPLETED`; B and C overlap in time (about 4 s total, not 7 s) | Serial execution, or not `COMPLETED` |
| E2E-C01 | Corner | Scenario | `samples/crash.json` with one worker killed mid-SLEEP | Attempt 1 `TIMED_OUT`; attempt 2 on the surviving worker; workflow `COMPLETED` | Workflow stuck `RUNNING`, or not `COMPLETED` after recovery |
| E2E-P03 | Positive | Scenario | `samples/retry.json`: `FLAKY` step fails twice, then succeeds with growing backoff | Workflow `COMPLETED`; `RETRYING` observed; gaps between attempts grow | Not `COMPLETED`, or no backoff between attempts |
| E2E-N01 | Negative | Scenario | `samples/failing.json`: retries exhausted | Workflow `FAILED` with step error | Workflow `COMPLETED` or stuck `RUNNING` |
| E2E-P04 | Positive | Scenario | Manual retry of the failed workflow | Workflow `FAILED` to `COMPLETED`; completed steps not re-run | Still `FAILED`, or completed steps re-run |
| E2E-P05 | Positive | Scenario | New application context resumes a `RUNNING` workflow | Workflow reaches `COMPLETED` after restart | Stuck or lost state after restart |

## 7. Failure Propagation (US2.5)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| FLP-P01 | Positive | STT | A step exhausts its retries | Step `FAILED`; workflow `FAILED` with the step's error message | Workflow not `FAILED`, or error message missing |
| FLP-P02 | Positive | STT | Dependents of a failed step | Never claimed or started; remain `PENDING` | Any dependent starts |
| FLP-N01 | Negative | STT | Failure while attempts remain | Step `RETRYING`; workflow stays `RUNNING` | Workflow `FAILED` before retries are exhausted |
| FLP-E01 | Edge | BVA | Failure on the last allowed attempt | Workflow `FAILED` on that attempt; no further attempt | Another attempt scheduled |
| FLP-C01 | Corner | Concurrency | Independent sibling completes after the workflow failed | Workflow stays `FAILED` | Workflow changes back to `RUNNING` or `COMPLETED` |
| FLP-C02 | Corner | Scenario | Unrelated ready step after the workflow failed | Not claimed | Claimed after workflow failure |

## 8. Manual Retry (US2.6)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| RTY-P01 | Positive | STT | Retry a `FAILED` workflow whose failure was transient | `FAILED` to `RUNNING` to `COMPLETED` | Stays `FAILED` |
| RTY-P02 | Positive | Scenario | Retry keeps completed steps and attempt history | Completed steps not re-run; failed step reset; prior attempts still listed | Completed step re-run, or history lost |
| RTY-P03 | Positive | API | `POST /api/workflows/{id}/retry` on a `FAILED` workflow | 200 with status `RUNNING`; reset step visible via the step endpoint | Non-200 response, or stale state |
| RTY-P04 | Positive | Scenario | `FLAKY` step succeeds after manual retry | Total attempt count reaches the threshold; step `COMPLETED` | Still fails after the threshold |
| RTY-N01 | Negative | DT | Retry a workflow that is `PENDING`, `RUNNING`, or `COMPLETED` | 409 with `application/problem+json`; no state change | 2xx response, or state changed |
| RTY-N02 | Negative | API | Retry an unknown workflow id | 404 with `application/problem+json` | 200 or 500 response |
| RTY-N03 | Negative | STT | Second retry after a successful retry | 409 | Accepted |
| RTY-N04 | Negative | Unit | Service-level retry of a non-failed workflow | Rejected; no rows changed | Any row changed |

## 9. Step Handlers (US1.6, US2.7)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| HND-P01 | Positive | Unit | `FLAKY` succeeds once total attempts reach `failUntilAttempt` | Output returned on the threshold attempt | Fails at or above the threshold |
| HND-N01 | Negative | Unit | `FLAKY` below the threshold | Fails the attempt | Succeeds early |
| HND-P02 | Positive | Unit | `HTTP` 2xx response | Status and body returned as output | Step fails |
| HND-N02 | Negative | Unit | `HTTP` non-2xx response | Attempt fails | Step succeeds |
| HND-N03 | Negative | Unit | `HTTP` response slower than `timeoutMs` | Attempt fails | Waits past the timeout |
| HND-N04 | Negative | Integration | `HTTP` to an unreachable URL | Attempt fails; workflow `FAILED` after one attempt | Retried beyond the configured attempts |
| HND-N05 | Negative | Unit | `HTTP` without `url` | Rejected | Accepted |
| HND-P03 | Positive | Unit | `LOG` returns its message as output | Output equals message | Output differs |
| HND-N06 | Negative | Unit | `LOG` with missing or non-string message | Rejected | Accepted |
| HND-P04 | Positive | Unit | `SLEEP` sleeps at least `durationMs`; zero returns immediately | Elapsed time at least the requested duration | Returns early |
| HND-N07 | Negative | Unit | `SLEEP` with missing or negative duration | Rejected | Accepted |
| HND-N08 | Negative | Unit | `SLEEP` interrupted | Step fails; interrupt flag restored | Interrupt flag lost |
| HND-P05 | Positive | Unit | `TRANSFORM` `merge`, `count`, `uppercase` | Correct output for each op | Wrong output |
| HND-N09 | Negative | Unit | `TRANSFORM` with missing or unsupported `op` | Rejected | Accepted |
| HND-P06 | Positive | Unit | `FAIL` always fails with configured or default message | Attempt fails with that message | Succeeds, or wrong message |
| HND-N10 | Negative | Unit | Unknown step type | Reported as unsupported; rejected at submit | Accepted at submit |
| HND-P07 | Positive | Unit | `HandlerRegistry` resolves types; lists them sorted | Correct handler; sorted list | Wrong handler, or unsorted |
| HND-N11 | Negative | Unit | Duplicate handler type registered | Rejected | Accepted |

## 10. Workflow List (US3.1)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| LST-P01 | Positive | API | List workflows | Newest first; paging metadata present | Wrong order, or metadata missing |
| LST-P02 | Positive | API | Filter by `status` | Only matching workflows returned | Other statuses returned |
| LST-N01 | Negative | API | Unknown `status` value | 400 | 200 |
| LST-N02 | Negative | API | Out-of-range `page` or `size` | 400 | 200 |

## 11. Platform, Persistence, and API Docs (US1.1, US1.2, US1.5, US2.1, US3.3, US3.4)

| ID | Type | Technique | Scenario | Success criteria | Failure criteria |
|---|---|---|---|---|---|
| PLT-P01 | Positive | Integration | Application context starts | Context loads | Startup failure |
| PLT-P02 | Positive | Integration | Flyway `V1` migration applied | All tables and ready and lease indexes exist | Missing table or index |
| PLT-P03 | Positive | Integration | Repositories store and read back workflow, steps, dependencies, attempts | Values match; deleting a workflow cascades | Data differs or rows remain |
| PLT-N01 | Negative | Integration | Invalid rows: duplicate step key, negative `remaining_deps`, self-dependency, cross-workflow dependency, invalid status | Rejected by the database | Row stored |
| PLT-N02 | Negative | Integration | Submit fails midway | Workflow and step rows rolled back | Partial rows remain |
| PLT-P04 | Positive | API | `GET /v3/api-docs` | 200; documents workflow endpoints and error responses | Missing paths or 404 |
| PLT-P05 | Positive | Unit | Worker ids | Hostname prefix plus random suffix; unique per replica | Duplicate ids on the same host |
| PLT-P06 | Positive | Integration | Worker shutdown during a step | In-flight step finishes within timeout; no new claims | New claim after stop, or step lost |
| PLT-P07 | Positive | Integration | Worker counters | Claim, completion, failure, retry, and reap counters visible at `/actuator/metrics` | Counter missing |

## 12. Manual Verification (not automated)

| ID | Scenario | Success criteria |
|---|---|---|
| MAN-01 | `docker-compose up --build` (US1.8) | All services healthy; `/actuator/health` returns `UP`; sample reaches `COMPLETED` |
| MAN-02 | `docker compose stop engine-worker` during a run (US3.4) | No steps lost; workflow reaches `COMPLETED` |
| MAN-03 | `docker kill` a worker during `crash.json` (US2.3, US3.5) | Attempt 1 `TIMED_OUT`; attempt 2 on the other worker; `COMPLETED` |
| MAN-04 | `./scripts/demo.sh` against the running stack (US3.2) | Every sample reaches its expected state; failing sample recovers after manual retry |
| MAN-05 | Swagger UI "Try it out" to submit and query (US1.5) | Submit and query succeed from the browser |

## 13. Known Gaps

These cases have no automated test yet. Add them before claiming full coverage.

| ID | Gap | Related scenario |
|---|---|---|
| GAP-01 | Backoff with attempt 0 or negative is not tested | BKF-N01 |
| GAP-02 | Claim at the exact `next_run_at` boundary is not tested | CLM-E01 |
| GAP-03 | Reaping at the exact lease expiry boundary is not tested | LSE-E01 |
| GAP-04 | Reaper racing a live worker on the same step is not tested | LSE-C01 |
| GAP-05 | Two concurrent completions for the same step are not tested (only sequential) | EXE-C01 |
| GAP-06 | Two workers racing on the same single last step is not tested directly | CLM-C02 |

## Traceability

| Story | Scenario groups |
|---|---|
| US1.1 | PLT-P01 |
| US1.2 | PLT-P02, PLT-P03, PLT-N01, PLT-N02 |
| US1.3 | SUB-*, CLM-P02 |
| US1.4 | SUB-N05, SUB-N06, SUB-N07 |
| US1.5 | PLT-P04, MAN-05 |
| US1.6 | HND-* |
| US1.7 | CLM-*, EXE-*, E2E-P01, E2E-P02 |
| US1.8 | MAN-01 |
| US2.1 | CLM-C01, CLM-C02, PLT-P05 |
| US2.2 | LSE-P01, LSE-N01, EXE-N01 to EXE-N04 |
| US2.3 | LSE-P02, LSE-N02, LSE-E01, LSE-E02, LSE-C01, E2E-C01, MAN-03 |
| US2.4 | BKF-*, CLM-N03, CLM-E01, E2E-P03 |
| US2.5 | FLP-*, E2E-N01 |
| US2.6 | RTY-*, E2E-P04 |
| US2.7 | HND-P01, HND-N01 to HND-N04 |
| US3.1 | LST-* |
| US3.2 | E2E-P01, E2E-P02, MAN-04 |
| US3.3 | PLT-P07 |
| US3.4 | PLT-P06, MAN-02 |
| US3.5 | E2E-* (all scenarios) |
| US3.6 | Documentation only |
