-- Continuous mode (SPEC §8, PLAN M5).

-- Whole-partition jobs: null means the job's COB dates (or a full copy) select the data.
ALTER TABLE jobs ADD COLUMN partitions text[];

-- One stream per (continuous job, table, target): what was last copied and where it stands.
CREATE TABLE continuous_state (
    name               text NOT NULL,
    table_name         text NOT NULL,
    target             text NOT NULL,
    last_poll_at       timestamptz,
    last_success_at    timestamptz,
    synced_as_of       timestamptz,          -- the target reflects the active DC as of this time
    watermarks         jsonb NOT NULL DEFAULT '{}',   -- append_watermark: shard -> max value copied
    job_id             text REFERENCES jobs (id) ON DELETE SET NULL,
    pending            jsonb,                -- applied when job_id succeeds: fingerprints, watermarks, as-of time
    wait_reason        text,
    last_error         text,
    polls              bigint NOT NULL DEFAULT 0,
    copies             bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (name, table_name, target)
);

-- Last copied state of each tracked source partition (both continuous modes).
CREATE TABLE partition_fingerprints (
    name          text NOT NULL,
    table_name    text NOT NULL,
    target        text NOT NULL,
    partition_id  text NOT NULL,
    fingerprint   text NOT NULL,
    checksum      text,                      -- verify: checksum only
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (name, table_name, target, partition_id)
);

-- Operator pause per continuous job (SPEC §7.5).
CREATE TABLE continuous_paused (
    name       text PRIMARY KEY,
    paused_by  text NOT NULL,
    paused_at  timestamptz NOT NULL DEFAULT now()
);
