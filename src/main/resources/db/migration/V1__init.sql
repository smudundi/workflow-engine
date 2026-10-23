CREATE TABLE workflow (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    definition    jsonb       NOT NULL,
    error         text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    started_at    timestamptz,
    completed_at  timestamptz,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE step (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id         uuid        NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    step_key            text        NOT NULL,
    type                text        NOT NULL,
    input               jsonb       NOT NULL DEFAULT '{}'::jsonb,
    output              jsonb,
    status              text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'RETRYING')),
    -- Unfinished upstream steps; the step is ready when this reaches zero.
    remaining_deps      integer     NOT NULL DEFAULT 0 CHECK (remaining_deps >= 0),
    -- Reset to 0 on manual retry; total attempts are counted from step_attempt.
    attempt             integer     NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts        integer     NOT NULL CHECK (max_attempts >= 1),
    backoff_initial_ms  bigint      NOT NULL CHECK (backoff_initial_ms >= 0),
    backoff_multiplier  double precision NOT NULL CHECK (backoff_multiplier >= 1.0),
    backoff_max_ms      bigint      NOT NULL CHECK (backoff_max_ms >= 0),
    next_run_at         timestamptz NOT NULL DEFAULT now(),
    lease_owner         text,
    -- Compared against database now() only, never container clocks.
    lease_expires_at    timestamptz,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    started_at          timestamptz,
    completed_at        timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT step_workflow_key_uq UNIQUE (workflow_id, step_key)
);

-- Partial index: only ready steps are candidates for claiming.
CREATE INDEX step_ready_idx ON step (next_run_at)
    WHERE status IN ('PENDING', 'RETRYING') AND remaining_deps = 0;

-- Partial index: lets the lease reaper find expired leases cheaply.
CREATE INDEX step_lease_idx ON step (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE TABLE step_dependency (
    workflow_id     uuid NOT NULL,
    step_key        text NOT NULL,
    depends_on_key  text NOT NULL,
    PRIMARY KEY (workflow_id, step_key, depends_on_key),
    -- Composite FKs ensure both endpoints belong to the same workflow.
    CONSTRAINT step_dependency_step_fk FOREIGN KEY (workflow_id, step_key)
        REFERENCES step (workflow_id, step_key) ON DELETE CASCADE,
    CONSTRAINT step_dependency_upstream_fk FOREIGN KEY (workflow_id, depends_on_key)
        REFERENCES step (workflow_id, step_key) ON DELETE CASCADE,
    CONSTRAINT step_dependency_no_self CHECK (step_key <> depends_on_key)
);

CREATE INDEX step_dependency_upstream_idx ON step_dependency (workflow_id, depends_on_key);

-- Append-only execution history; one row per claim, closed when the attempt ends.
CREATE TABLE step_attempt (
    id           bigserial PRIMARY KEY,
    step_id      uuid        NOT NULL REFERENCES step (id) ON DELETE CASCADE,
    attempt_no   integer     NOT NULL CHECK (attempt_no >= 1),
    worker_id    text        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    error        text,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz
);

CREATE INDEX step_attempt_step_idx ON step_attempt (step_id);
