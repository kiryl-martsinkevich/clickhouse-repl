-- clickhouse-repl control plane (SPEC §3, §7; PLAN M4).

-- Every configuration ever activated; jobs run with the version they were created with.
CREATE TABLE config_versions (
    version     text PRIMARY KEY,           -- sha-256 of the content
    content     text NOT NULL,
    loaded_at   timestamptz NOT NULL DEFAULT now(),
    loaded_by   text NOT NULL,
    activated_at timestamptz NOT NULL DEFAULT now()   -- the latest activated version is current everywhere
);

CREATE TABLE jobs (
    id               text PRIMARY KEY,
    kind             text NOT NULL,         -- adhoc | continuous | refresh
    queue            text NOT NULL,         -- adhoc | continuous
    requester        text NOT NULL,
    target           text NOT NULL,
    dataset          text,
    tables           text[] NOT NULL,
    cob_dates        date[] NOT NULL DEFAULT '{}',
    mode             text NOT NULL DEFAULT 'replace',
    state            text NOT NULL,         -- PENDING PLANNING RUNNING FINALISING SUCCEEDED FAILED BLOCKED CANCELLING CANCELLED
    config_version   text NOT NULL REFERENCES config_versions (version),
    coalesce_key     text NOT NULL,
    idempotency_key  text,
    estimated_bytes  bigint NOT NULL DEFAULT 0,
    reason           text,                  -- BLOCKED / FAILED explanation (never data values)
    lease_owner      text,
    lease_until      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz
);
CREATE UNIQUE INDEX jobs_idempotency ON jobs (requester, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX jobs_by_state ON jobs (state, queue, created_at);
CREATE INDEX jobs_by_requester ON jobs (requester, created_at);

-- Everyone attached to a job (the submitter and coalesced duplicates).
CREATE TABLE job_requesters (
    job_id     text NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    requester  text NOT NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, requester)
);

-- One target partition to rebuild (SPEC §5.4).
CREATE TABLE partition_tasks (
    id            bigserial PRIMARY KEY,
    job_id        text NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    table_name    text NOT NULL,
    target_index  int NOT NULL,
    partition_id  text NOT NULL,
    staging       text NOT NULL,
    chunk_total   int NOT NULL,
    chunk_done    int NOT NULL DEFAULT 0,
    rows_copied   bigint NOT NULL DEFAULT 0,
    rows_total    bigint,
    state         text NOT NULL,            -- WAITING READY ASSEMBLING DONE FAILED
    lease_owner   text,
    lease_until   timestamptz,
    error         text,
    UNIQUE (job_id, table_name, target_index, partition_id)
);

-- One source read (SPEC §5.3).
CREATE TABLE chunks (
    id                 bigserial PRIMARY KEY,
    job_id             text NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    partition_task_id  bigint NOT NULL REFERENCES partition_tasks (id) ON DELETE CASCADE,
    table_name         text NOT NULL,
    chunk_key          text NOT NULL,
    shard              text NOT NULL,
    spec               jsonb NOT NULL,
    staging            text NOT NULL,
    state              text NOT NULL,       -- QUEUED RUNNING PREEMPTED DONE FAILED CANCELLED
    attempts           int NOT NULL DEFAULT 0,
    failures           int NOT NULL DEFAULT 0,
    preemptions        int NOT NULL DEFAULT 0,
    not_before         timestamptz NOT NULL DEFAULT now(),
    lease_owner        text,
    lease_until        timestamptz,
    wait_reason        text,
    rows_copied        bigint,
    error              text,
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chunks_claimable ON chunks (state, not_before);
CREATE INDEX chunks_by_job ON chunks (job_id, state);

-- The "last copied" registry (SPEC §7.3).
CREATE TABLE copy_registry (
    id              bigserial PRIMARY KEY,
    target          text NOT NULL,
    table_name      text NOT NULL,
    partition_id    text NOT NULL,
    cob_date        date,
    job_id          text NOT NULL,
    requester       text NOT NULL,
    rows_copied     bigint NOT NULL,
    realm           text NOT NULL,
    key_version     int NOT NULL,
    config_version  text NOT NULL,
    copied_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX copy_registry_lookup ON copy_registry (target, table_name, cob_date, copied_at DESC);

-- Append-only audit trail, written in the same transaction as the change it records.
CREATE TABLE audit_log (
    id       bigserial PRIMARY KEY,
    at       timestamptz NOT NULL DEFAULT now(),
    actor    text NOT NULL,
    action   text NOT NULL,
    job_id   text,
    details  jsonb NOT NULL DEFAULT '{}'
);

-- Operator controls shared by every process (SPEC §4.6, §4.7).
CREATE TABLE ops_flags (
    name    text PRIMARY KEY,               -- paused | failover
    value   jsonb NOT NULL,
    set_by  text NOT NULL,
    set_at  timestamptz NOT NULL DEFAULT now()
);

-- Cluster-wide read admission tokens (SPEC §4.4 concurrency limits across processes).
CREATE TABLE read_tokens (
    query_id     text PRIMARY KEY,
    host         text NOT NULL,
    holder       text NOT NULL,
    lease_until  timestamptz NOT NULL
);
