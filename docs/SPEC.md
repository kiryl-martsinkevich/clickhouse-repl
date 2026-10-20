# clickhouse-repl — Design Specification

Status: **Draft for review** · Owner: TBD · Last updated: 2026-10-20

`clickhouse-repl` copies data from a production ClickHouse deployment into one or
more non-production ClickHouse deployments (QA, UAT, perf, dev). It masks
sensitive columns consistently on the way, keeps a configured set of tables up to
date continuously, and lets QA engineers request ad-hoc copies (a table or a
dataset for a COB date) from a self-service portal.

The overriding requirement is that **production workloads take precedence over
replication at all times**. When production is busy, replication waits; when
production becomes busy while replication is running, replication backs off.

---

## 1. Goals and non-goals

### 1.1 Goals

| # | Goal |
|---|------|
| G1 | Copy a configured list of databases / tables from a prod cluster to one or more non-prod clusters. |
| G2 | Mask configured columns **consistently**: the same input value in the same masking *domain* always produces the same output, across tables, jobs and runs, so joins and referential integrity survive masking. |
| G3 | **Continuous mode**: a configuration-defined set of tables is kept near-real-time in sync (seconds to minutes of lag). |
| G4 | **Ad-hoc mode**: a QA user selects a table or a predefined dataset and one or more COB dates in a portal, submits, and the job runs in the background with visible progress. |
| G5 | **Production protection**: replication never competes with production. It waits while prod is busy, is pre-empted when prod becomes busy, and fails closed when prod health is unknown. |
| G6 | Auditability: every copy is attributable (who, what, when, which masking key version, which config version). |
| G7 | Safe to retry: every unit of work is idempotent; crashes and pre-emptions lose time, never correctness. |

### 1.2 Non-goals (v1)

- Writing anything to production (data, schema, or settings). The prod user is read-only.
- Sub-second change data capture. ClickHouse has no native CDC; v1 is polling-based (§8).
- Non-ClickHouse targets.
- Arbitrary free-form SQL extracts by end users (a restricted, validated filter may come later, §7.6).
- Reversible "un-masking" for end users.

### 1.3 Honest framing of "no impact"

Any read consumes *some* CPU, IO, memory and network on the node that serves it.
"Zero impact" is not physically achievable; what this design guarantees is:

1. Replication reads are **bounded** by limits enforced inside ClickHouse itself (§4.2), so even a bug in the tool cannot exceed them.
2. Replication reads **only start** when every watched prod signal is below threshold for a sustained window (§4.4).
3. Replication reads are **killed within seconds** when prod becomes busy (§4.5).
4. Preferably, reads are served by a **dedicated replica that serves no production traffic** (§4.3), which reduces residual impact to replication-fetch and network effects.

The acceptance criterion for G5 is an agreed impact budget, e.g. *"prod p99 query
latency does not move by more than X% while replication runs"*, verified by a load
test (see PLAN M7).

---

## 2. Terminology

| Term | Meaning |
|------|---------|
| **Source** | The production ClickHouse cluster. Only ever read. |
| **Target** | A non-production ClickHouse cluster. Written by the tool. |
| **Table spec** | Config entry describing one source table: target mapping, COB column, column masking policies, continuous settings. |
| **Dataset** | A named group of table specs that are usually copied together (e.g. `trading-eod`). |
| **COB date** | Close-of-business / business date. Each table spec maps COB to a column (e.g. `trade_date`, `cob_date`). |
| **Job** | A user- or scheduler-initiated request (ad-hoc copy, continuous poll, scheduled refresh). |
| **Chunk** | The unit of work: one source table × one partition × (optional) sort-key range × (optional) COB filter. |
| **Masking domain** | A named value space (e.g. `customer_id`, `email`). Consistency is guaranteed within a domain. |
| **Realm** | A masking key scope (e.g. `nonprod`). All targets in one realm see identical masked values. |
| **Prod Guard** | The component that decides whether prod may be read *now*, and kills reads when it may not. |

---

## 3. Architecture

### 3.1 Components

```
                 ┌──────────────────────────── Control plane (Kotlin / Spring Boot) ───────────────────────────┐
  QA (browser) ──► Portal (React SPA) ──► REST API ──► Job service ──► Planner ──► Postgres (jobs, chunks,      │
     OIDC SSO    │                         ▲            ▲                │         leases, audit, guard state)   │
                 │   CLI ─────────────────┘            │ Scheduler      │                                       │
                 │                                      │ (continuous)   ▼                                       │
                 │                               Config loader    Prod Guard (leader) ── polls ──► PROD nodes    │
                 └──────────────────────────────────────────────────────┬──────────────────────────────────────┘
                                                                        │ admission tokens / kill signals
                 ┌──────────────── Workers (same binary, role=worker, N replicas) ──────────────┐
                 │  claim chunk ─► stream SELECT from prod read replica (RowBinary)             │
                 │             ─► mask in JVM (masking engine, keys from Vault/KMS)             │
                 │             ─► INSERT into target staging table ─► REPLACE PARTITION         │
                 └──────────────────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|-----------|----------------|
| **Config loader** | Loads and validates YAML config (§9), resolves secrets, exposes an immutable versioned snapshot. |
| **REST API** | Catalog, estimates, job submission/cancel/status, guard status, continuous status, admin operations (§7.5). |
| **Portal** | React + TypeScript SPA behind OIDC. Thin client of the REST API. |
| **Job service** | Validates requests (RBAC, quotas, policy completeness), creates jobs, coalesces duplicates. |
| **Planner** | Turns a job into chunks using **metadata only** (`system.parts`, `EXPLAIN ESTIMATE`). Never scans data. |
| **Scheduler** | Creates continuous-mode and scheduled-refresh jobs on their intervals (§8). |
| **Prod Guard** | One active instance per source cluster (leader election via Postgres advisory lock). Polls prod signals, computes GREEN/AMBER/RED, hands out admission tokens, triggers pre-emption (§4). |
| **Workers** | Claim chunks from the Postgres queue, obtain an admission token, stream → mask → write, heartbeat, finalise. |
| **Postgres** | System of record for jobs, chunks, leases, watermarks, partition fingerprints, guard state history, audit log. |
| **CLI** | `validate-config`, `dry-run` (plan + estimate, no data read), `submit`, `status`, `cancel`. |

The control plane and workers are the **same artefact** started with different
roles (`api`, `scheduler`, `guard`, `worker`, or `all` for local/dev). Postgres is
the only stateful dependency; there is no message broker.

### 3.2 Technology choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language / runtime | Kotlin 2.x on JDK 21 (virtual threads) | Team preference (JVM); good streaming IO, mature crypto. |
| Framework | Spring Boot 3.x (Web, Security/OIDC, Actuator) | OIDC, metrics and ops endpoints out of the box. |
| ClickHouse client | `com.clickhouse:client-v2` (HTTP, streaming `RowBinaryWithNamesAndTypes`) | Official client, streaming read and insert, per-query settings and `query_id`. |
| State | PostgreSQL 15+, Flyway migrations, jOOQ | `FOR UPDATE SKIP LOCKED` queue, advisory locks, transactional audit. |
| Crypto | JCA HMAC-SHA256, Bouncy Castle (FF1 FPE) | Standard, FIPS-friendly primitives. |
| Config | YAML + JSON Schema, Jackson Kotlin module | Reviewable in git, validated in CI. |
| Portal | React 18 + TypeScript + Vite + TanStack Query | Simple SPA; served by the control plane. |
| Observability | Micrometer → Prometheus, OpenTelemetry traces, structured JSON logs | |
| Tests | JUnit 5, Kotest property tests, Testcontainers (ClickHouse ×2, Postgres), Playwright | Real ClickHouse in tests; "prod" and "target" containers. |
| Deployment | Container image, Kubernetes (Helm chart) | 2× api/scheduler/guard, N× workers. |

---

## 4. Production protection — Prod Guard

Defence in depth. Each layer is sufficient to stop gross impact on its own; together
they make replication strictly subordinate to production. **Every layer fails closed.**

### 4.1 Overview

| Layer | Where enforced | What it does |
|-------|---------------|--------------|
| L1 | Inside prod ClickHouse | Hard resource limits on the replication users (cannot be bypassed by the tool). |
| L2 | Topology | Reads go to a designated, preferably traffic-free, replica per shard. |
| L3 | Prod Guard admission | A chunk may start only when prod has been "quiet" for a stable window. |
| L4 | Prod Guard pre-emption | Running chunks are killed when prod becomes busy; they are retried later. |
| L5 | Operations | Blackout windows, global kill switch, circuit breaker. |

### 4.2 L1 — limits enforced by ClickHouse

Two dedicated users on prod. Both are provisioned by the prod DBAs from DDL shipped
with the tool (`deploy/prod-ddl.sql`); the tool never runs DDL on prod.

```sql
-- Settings profile for replication reads. CONST / MAX constraints mean the client
-- can only make things *more* restrictive, never less.
CREATE SETTINGS PROFILE repl_reader_profile SETTINGS
    readonly = 2,                                   -- no writes; settings changes allowed but bounded below
    max_threads = 2 MAX 2,
    max_memory_usage = 4000000000 MAX 4000000000,   -- 4 GB per query
    max_execution_time = 900 MAX 900,               -- 15 min per query (chunks are sized well below)
    max_bytes_to_read = 50000000000 MAX 50000000000,
    max_network_bandwidth = 50000000 MAX 50000000,  -- 50 MB/s per query
    max_network_bandwidth_for_user = 100000000 MAX 100000000,
    max_concurrent_queries_for_user = 2 MAX 2,
    priority = 10 MIN 10,                           -- lowest precedence (see note)
    use_uncompressed_cache = 0 CONST,               -- do not evict prod's hot uncompressed blocks
    min_bytes_to_use_direct_io = 10000000 MAX 10000000,   -- large reads bypass the OS page cache
    enable_filesystem_cache = 0 CONST,              -- tiered/S3 storage: do not fill prod's FS cache
    read_from_filesystem_cache_if_exists_otherwise_bypass_cache = 1 CONST,
    force_index_by_date = 1,                        -- reject reads that cannot prune by partition/minmax (see notes)
    workload = 'replication' CONST;                 -- CH >= 24.10 workload scheduling (see below)

CREATE USER repl_reader IDENTIFIED WITH sha256_password BY '…'
    HOST IP '10.20.0.0/16'                          -- worker egress range only
    SETTINGS PROFILE repl_reader_profile;
GRANT SELECT ON trading.* TO repl_reader;           -- exactly the configured databases
GRANT SELECT(database, table, partition, partition_id, name, rows, bytes_on_disk,
             modification_time, active, min_date, max_date) ON system.parts TO repl_reader;

CREATE QUOTA repl_reader_quota FOR INTERVAL 1 hour
    MAX queries = 2000, read_rows = 20000000000, read_bytes = 2000000000000, execution_time = 7200
    TO repl_reader;

-- Guard user: reads system tables and can kill replication queries only.
CREATE USER repl_guard IDENTIFIED WITH sha256_password BY '…' HOST IP '10.20.0.0/16'
    SETTINGS readonly = 2, max_threads = 1, max_execution_time = 10, max_memory_usage = 200000000;
GRANT SELECT ON system.processes, system.metrics, system.asynchronous_metrics,
               system.merges, system.replication_queue, system.replicas, system.mutations
    TO repl_guard;
GRANT KILL QUERY ON *.* TO repl_guard;  -- used only with WHERE user = 'repl_reader' (enforced in code + reviewed)
```

Notes:

- **`priority`**: in ClickHouse a lower number means higher priority, and `0` means "no
  priority". Priority only takes effect against queries that also have a non-zero
  priority, so it is helpful only if prod user profiles set `priority = 1` (or similar).
  This is a recommendation to prod DBAs, not a dependency.
- **Workload scheduling** (`CREATE RESOURCE` / `CREATE WORKLOAD`, ClickHouse ≥ 24.10 for
  IO; CPU scheduling in later releases): when available, put `repl_reader` in a
  `replication` workload with minimal weight under the production workload, so the server
  itself shares disk/network/CPU in prod's favour. Exact DDL depends on the prod version
  and is an open question (§13).
- **`force_index_by_date = 1`** makes ClickHouse refuse a replication query that cannot
  prune by the partition key / minmax index, so an accidental full-table scan of a large
  fact table is refused by the server. Table specs must therefore have a COB column or
  partition filter usable for pruning; this is checked at config validation (§9.3) and
  verified per table in integration tests. The only exception is `full_copy` tables
  (unpartitioned reference data) below a configured size cap, for which the worker relaxes
  this one setting per query; all other L1 limits still apply.
- Chunk sizing (§5.3) keeps normal chunks far below these limits, so hitting an L1 limit
  indicates a planner bug and fails the chunk (it is not retried blindly).

### 4.3 L2 — read source selection

- Each prod shard lists **read replicas** in config, in preference order. The recommended
  deployment is one extra replica per shard that is **excluded from production load
  balancers** and exists for replication/backup reads.
- Reads go to the shard's *local* tables on that replica. The tool **never reads through a
  `Distributed` table**, because that would fan out to every shard at once and use
  production's shard-selection policy.
- If no read replica is configured for a shard, the tool falls back to the replica with the
  lowest guard score, and the guard thresholds for that host apply in full.
- A read replica whose `system.replicas.absolute_delay` exceeds a threshold is skipped (data
  would be stale).

### 4.4 L3 — admission control

The guard leader polls **every watched prod host** (not only the read replica — a heavy
query anywhere on prod blocks replication, per requirement) every `poll_interval`
(default 5 s) with small, fixed queries against in-memory system tables. It polls hosts
directly rather than with `clusterAllReplicas`, so an unreachable host is an explicit
"unknown" signal instead of a failed distributed query.

**Signals and default thresholds** (all configurable per cluster):

| Signal | Source | Default threshold (blocks when exceeded) |
|--------|--------|-------------------------------------------|
| Heavy foreign query running | `system.processes` (queries **not** from `repl_*` users) | any query with `elapsed > 300 s` **or** `memory_usage > 20 GiB` **or** `read_rows > 5e9` |
| Foreign query concurrency | `system.processes` count | `> 50` |
| CPU utilisation | `system.asynchronous_metrics` (`OSUserTimeNormalized` + `OSSystemTimeNormalized`) | `> 0.60` |
| Load average per core | `LoadAverage1` / `NumberOfCPUCores`… | `> 0.70` |
| Memory | `MemoryTracking` vs `max_server_memory_usage` | `> 0.70` |
| Background merges / mutations | `system.merges`, `system.mutations` (not done) | `> 20` merges or any mutation `> 100 GB` |
| Replication health | `system.replication_queue` size, `system.replicas.absolute_delay` | `> 100` entries or `> 30 s` delay |
| Insert pressure | `system.metrics` `DelayedInserts`, `parts_to_delay_insert` proximity | `DelayedInserts > 0` |
| Blackout window | config cron | inside window |

**Guard states** (per source cluster, also per host for diagnostics):

| State | Meaning | Allowed |
|-------|---------|---------|
| **GREEN** | All signals below threshold continuously for `stable_window` (default 60 s). | Data reads (chunks), metadata queries. |
| **AMBER** | Signals below threshold but not yet stable, or within 80–100 % of a threshold. | Cheap metadata queries only (`system.parts`, `EXPLAIN ESTIMATE`). No new chunks. |
| **RED** | Any threshold exceeded, blackout window, kill switch, circuit open. | Nothing except the guard's own polls. Running chunks are pre-empted. |
| **UNKNOWN** | A watched host unreachable, poll failed, poll stale (> 3× interval), clock skew. | **Treated as RED** (fail closed). |

**Hysteresis**: RED → GREEN requires `stable_window` of continuously clean samples, so the
guard does not flap. Thresholds for *entering* RED are the configured values; *leaving*
RED requires signals to drop below `release_ratio × threshold` (default 0.8).

**Admission tokens**: in GREEN the guard grants tokens up to `concurrency.global` (default
2) and `concurrency.per_host` (default 1) concurrent chunk reads. A worker must hold a
token (a row in Postgres with a short lease) to issue a data read. Tokens are revoked when
the state leaves GREEN.

**Wait reasons** are recorded on each waiting chunk and shown to users, e.g. *"Waiting
for prod: query 7f3a… by user `risk_batch` running 14 min, 38 GiB on ch-prod-s2-r1"*. Query
text is never shown (it may contain sensitive literals); only user, duration, memory and
host.

### 4.5 L4 — pre-emption

- While any chunk runs, the guard keeps polling. When the cluster turns RED (or a specific
  host the chunk is reading from turns RED), the guard:
  1. revokes the chunk's token,
  2. cancels the read by closing the HTTP connection (the client sets
     `cancel_http_readonly_queries_on_client_close = 1`), and
  3. issues `KILL QUERY WHERE user = 'repl_reader' AND query_id = '<chunk query id>' ASYNC`
     as a backstop after `grace` (default 5 s).
- Workers tag every prod query with a deterministic `query_id` (`chrepl-<job>-<chunk>-<attempt>`)
  so kills are precise and auditable. The guard refuses to kill any query whose user is not
  `repl_reader` (checked in code, covered by tests).
- A pre-empted chunk returns to the queue in state `PREEMPTED` with exponential backoff.
  Because writes go to a per-chunk staging table (§5.4), nothing partial is ever visible on
  the target and nothing is lost.
- Pre-emption latency target: **≤ poll_interval + 5 s** from prod becoming busy to the
  replication query ending.

### 4.6 L5 — operational controls

- **Blackout windows**: cron + duration per cluster (e.g. EOD batch, month-end). Guard is RED inside them.
- **Global kill switch**: `POST /api/v1/admin/pause` (admin role) or a config flag; guard goes RED immediately for all clusters; persisted in Postgres so it survives restarts.
- **Circuit breaker**: if more than `N` pre-emptions occur within `T` (default 5 in 30 min), the guard stays RED for `open_for` (default 1 h) and alerts. This stops replication from repeatedly nudging a prod system that is near its limits.
- **No override**: there is deliberately **no** API to force a job through a RED guard. Admins can only pause, never bypass.

### 4.7 Guard self-impact

The guard's own polling is bounded: ≈ 6 small queries per host per 5 s against in-memory
system tables, executed by `repl_guard` with `max_threads = 1` and a 10 s timeout. This
cost is measured in the M7 load test and is part of the impact budget.

---

## 5. Data path

### 5.1 Why stream through the worker

Considered and rejected: `INSERT INTO target … SELECT … FROM remoteSecure(prod, …)` run on
the target. It is fast, but:

- masking would have to be expressed in SQL, putting masking keys/salts into query text and
  therefore into prod and target `system.query_log`;
- the read is issued by the target cluster, outside the guard's token and kill control;
- prod credentials would have to live on non-prod servers.

Chosen: workers stream rows from prod in `RowBinaryWithNamesAndTypes`, mask in the JVM,
and stream into the target with `INSERT … FORMAT RowBinary`. Masking keys live only in
worker memory. Raw prod data is never written to worker disk; buffers are bounded and
backpressure flows from target insert to prod read.

### 5.2 Schema synchronisation

1. Read `SHOW CREATE TABLE db.t` and `system.columns` from the prod read replica.
2. Rewrite for the target:
   - `ON CLUSTER` name → target cluster name;
   - `Replicated*MergeTree('/clickhouse/tables/{shard}/…', '{replica}')` → target ZooKeeper path template (or non-replicated engine if the target is single-node);
   - storage policy / TTL / settings overrides from config (e.g. shorter TTL on QA);
   - `Distributed` tables recreated pointing at the target cluster;
   - masked columns keep their type (masking is type-preserving, §6.4).
3. Create database/table on the target if missing.
4. **Drift detection**: if prod and target columns differ, or prod has a column with no
   masking policy, the job **blocks** with reason `SCHEMA_DRIFT` / `UNCLASSIFIED_COLUMN`
   until the config is updated (and, for target-side drift, `ALTER TABLE` is applied on
   the target by the tool — targets are disposable, prod is never altered).
5. Materialized views (per table spec, `mv_mode`):
   - `base_only` (default): copy the base table; target MVs compute their own targets. MV target tables are excluded from copying to avoid double data.
   - `targets_only`: copy MV target tables directly; MVs on the target are created but their source tables are not copied.
   - `none`: MVs are not created on the target.

### 5.3 Planning chunks (metadata only)

```sql
SELECT partition_id, partition, sum(rows) AS rows, sum(bytes_on_disk) AS bytes,
       max(modification_time) AS mtime, count() AS parts,
       groupArray(name) AS part_names
FROM system.parts
WHERE active AND database = {db:String} AND table = {t:String}
GROUP BY partition_id, partition
```

- COB filters are mapped to partitions using the partition key expression (e.g. `toYYYYMM(trade_date)`) and, where the COB column is in the minmax index, `min_date/max_date`; `EXPLAIN ESTIMATE` gives rows/marks for the filtered read.
- A chunk targets ≤ `chunk.max_bytes` (default 2 GiB compressed) and ≤ `chunk.max_seconds` (default 120 s estimated at the per-query bandwidth cap). Larger partitions are split into **sort-key ranges** derived from the primary index (the `mergeTreeIndex()` table function where the prod version supports it, otherwise evenly spaced boundaries between the per-part min/max of the first sort-key column). Planning reads index marks, never column data.
  Ranges are on the sort key, not on part names, so they stay valid while parts merge.
- Every data read is of the form:
  ```sql
  SELECT <columns> FROM db.t
  WHERE _partition_id = {pid:String}
    [AND <cob_column> IN ({cob:Array(Date)})]
    [AND <sort_key_range>]
  SETTINGS max_threads = …, …            -- within L1 bounds
  ```
  `_partition_id` plus `force_index_by_date` guarantees partition pruning.

### 5.4 Writing: idempotent and atomic

For each source partition `P` being copied to target table `T`:

1. For each chunk `c` of `P`: create (or truncate) `T__stg_<job>_<c>` (`CREATE TABLE … AS T`, same engine family, non-replicated), stream masked rows into it. On retry it is truncated first → idempotent.
2. When all chunks of `P` are done, assemble `T__stg_<job>_P`:
   - **Partition-aligned copy** (whole partition requested, or the COB filter covers the whole partition): `ALTER TABLE T__stg_P ATTACH PARTITION ID 'P' FROM T__stg_<job>_<c>` for each chunk.
   - **Sub-partition copy** (e.g. one COB date inside a monthly partition): first copy the *other* rows of `P` already present on the target into the staging table (`INSERT INTO T__stg_P SELECT * FROM T WHERE _partition_id = 'P' AND NOT (<cob filter>)` — target-side work only), then attach chunk staging partitions.
3. `ALTER TABLE T REPLACE PARTITION ID 'P' FROM T__stg_P` — atomic swap on the target. Readers on the target see either the old or the new partition, never a mix.
4. Drop staging tables. A janitor drops orphaned `__stg_` tables older than 24 h.

Constraints and validation:

- Columns used in the **partition key expression must not be masked** (the partition id would change). Validation error at config load.
- Masked columns in the sort key are allowed (data is re-sorted on insert).
- Concurrent writers to the same `(target, table, partition)` (e.g. a continuous poll and an ad-hoc job) are serialised by a Postgres advisory lock taken during step 3.
- Consistency is **per partition, per read**: a partition split into several chunks is not a point-in-time snapshot if prod is mutated mid-copy. Continuous mode (§8.2) re-checks fingerprints and re-copies if the partition changed during the copy.

### 5.5 Sharded sources

- The planner creates chunks per shard (each shard's local table and partitions).
- If the target has the same shard count, shard *i* → target shard *i*; otherwise rows are inserted into a target `Distributed` table (or the target is single-shard). Configurable per cluster mapping.

---

## 6. Masking

### 6.1 Policy model

Every column of every configured table must have an explicit policy:

- `pass` — copied as-is (an explicit statement that the column is not sensitive), or
- a masking rule referencing a **domain** (and optionally a strategy override).

A table may set `unlisted_columns: pass` only together with `classification: non_pii`
and an `approved_by` field (e.g. reference data). Otherwise **unlisted columns are an
error** — at config validation for known columns, and at runtime (`UNCLASSIFIED_COLUMN`)
for columns added to prod later. This is the main defence against new PII silently leaking
to non-prod.

An optional heuristic **PII scanner** (`validate-config --scan-pii`) flags `pass` columns
whose names match patterns (`email`, `phone`, `name`, `iban`, `ssn`, `dob`, `address`, …)
for reviewer attention. It is advisory only.

### 6.2 Consistency and keys

- All deterministic strategies derive their randomness from
  `HMAC-SHA256(K[realm][version][domain], canonical(value))`, where
  `K[realm][version][domain] = HKDF(master_key[realm][version], info = domain)`.
- `canonical(value)` is a type-aware encoding (integers as decimal strings, dates as ISO-8601,
  strings as UTF-8 NFC; optional per-domain normalisation: trim, lower-case). Because integers
  and strings canonicalise to the same text, a `customer_id` stored as `UInt64` in one table and as
  `String` in another masks consistently when both reference domain `customer_id`.
- **Same realm + same domain + same key version ⇒ same output**, across tables, jobs, runs and
  targets. Different realms (e.g. `nonprod` vs `vendor-share`) produce unrelated outputs.
- Master keys live in Vault (Transit/KV) or a cloud KMS; workers fetch them at start-up into
  memory, never log them, and never send them to ClickHouse. The audit log records the key
  version used for each chunk.
- **Key rotation** changes every masked value, which breaks consistency with data already
  on targets. Rotation is therefore a planned event: bump the version, then run a full
  re-copy of affected tables (a `rekey` job type). Mixed versions in one target table are
  prevented: the target records the key version per table in `repl_meta.tables`.

### 6.3 Strategies

| Strategy | Deterministic | Output | Typical use |
|----------|---------------|--------|-------------|
| `hash` | yes | hex / base64 of HMAC, optionally truncated to N chars | opaque identifiers where format doesn't matter |
| `tokenize` | yes | format-preserving: digits → digits, letters → letters (case kept), separators and length kept | account numbers, IDs with validation-free formats, phone numbers |
| `fpe_ff1` | yes, **bijective** | NIST FF1 format-preserving encryption over a configured alphabet | identifiers that must stay **unique** (primary keys) — reversible by key holders, so restricted to admin-approved domains |
| `email` | yes | `tokenize(local)@<domain-mode>`; domain kept, mapped to `example.com`, or tokenized | emails |
| `dictionary` | yes | value from a versioned dictionary, index = HMAC mod size | names, cities, company names |
| `date_shift` | yes (per entity) | date ± offset; offset = HMAC(entity value) mod (2·max_days+1) − max_days | dates of birth, event dates — intervals within one entity preserved |
| `numeric_noise` | yes (per entity or per value) | value × (1 ± p), rounded to the column's scale, clamped to type range | amounts, balances where distribution matters |
| `partial` | n/a | keep first/last N characters, replace the rest with a mask char | PAN-like display values |
| `null` | n/a | NULL (Nullable) or type default | free text, notes |
| `constant` | n/a | fixed literal | values that must exist but carry no meaning |

`tokenize` and `hash` are not bijective: on small value spaces collisions are possible.
For columns that must remain unique, use `fpe_ff1`, or enable `collision_check: true` on
the domain (the worker counts distinct inputs vs outputs per chunk and fails the chunk on a
collision; this is per chunk, not global).

Strategies that need **another column of the same row** (`date_shift`, entity-seeded
`numeric_noise`) declare `entity_column`; the entity value's canonical form seeds the
derivation, so all dates of the same customer shift by the same offset everywhere.

### 6.4 Types

- Supported: all integer widths, Decimal, Float, String, FixedString, Date/Date32/DateTime/DateTime64, UUID, IPv4/IPv6, Enum (pass or `constant` only), and wrappers `Nullable`, `LowCardinality`, `Array(T)`, `Map(K, V)` (mask keys and/or values), `Tuple(...)` (per element), and `JSON`/`Object` and JSON-in-String via path selectors (`$.contact.email`).
- Masking is **type-preserving**: output fits the column type (length for FixedString, range for integers, scale for Decimal). Integer `tokenize` keeps the digit count and a non-zero leading digit; if the result would overflow the type, it is reduced modulo the type's range (documented loss of cross-type consistency for 20-digit UInt64 values).
- NULL in → NULL out (unless strategy is `constant`).

### 6.5 Performance

- Masking is CPU-bound in the worker, never on ClickHouse.
- Per-domain LRU caches (`value → masked`) exploit the high repetition of IDs in fact tables.
- Target throughput per worker: ≥ 50 MB/s uncompressed for typical tables. In practice the L1 bandwidth cap (§4.2) is the bottleneck by design.

---

## 7. Ad-hoc mode and portal

### 7.1 User flow

1. QA signs in (OIDC SSO). The portal shows the **catalog** filtered by their RBAC: datasets, tables, and the targets they may write to.
2. QA picks a **dataset** (e.g. `trading-eod`) or a single **table**, then **COB date(s)**: a single date, a range, or a list. Tables in a dataset that are `full_copy` (reference data) ignore COB.
3. QA picks a **target** environment (e.g. `qa1`) and mode: `replace` (default; replace the selected COB slice on the target) or `skip_if_present`.
4. The portal requests an **estimate** (`POST /api/v1/estimates`): partitions, rows, compressed bytes per table, from metadata only, plus the current guard state and queue depth.
5. QA submits. Quotas are checked (§7.4). The job appears in "My jobs" with state, **queue position**, **wait reason** when waiting for prod, per-table progress (chunks done/total, rows, bytes) and an ETA. QA can **cancel**.
6. On completion the user is notified (portal notification; optional email/Slack webhook), with a summary and the masking key version used.

### 7.2 Job lifecycle

```
PENDING ──plan──► QUEUED ──token──► RUNNING ──all chunks done──► FINALISING ──► SUCCEEDED
   │                │ ▲               │  │
   │                │ └──PREEMPTED────┘  └──error (retries exhausted)──► FAILED
   │                ▼
   │         WAITING_FOR_PROD (guard not GREEN; reason shown)
   └──────── BLOCKED (schema drift / unclassified column / quota) ──► needs config change or user action
any state ──cancel──► CANCELLING ──► CANCELLED (running reads killed, staging dropped)
```

Chunk states mirror this (`QUEUED`, `RUNNING`, `PREEMPTED`, `DONE`, `FAILED`). Chunk retries:
up to 5 attempts with exponential backoff for transient errors; pre-emptions do not count as
failed attempts.

### 7.3 Scheduling and fairness

- Two queues: `continuous` and `adhoc`, served by weighted fair share (default 3:1) when the guard grants tokens. Continuous polls are small and latency-sensitive; ad-hoc jobs are larger.
- Within `adhoc`: per-user and per-team concurrency caps (default 2 running jobs per user), FIFO otherwise.
- **Coalescing**: identical requests (same table, COB, target) already queued or running are merged; the second requester is attached to the existing job.
- Chunks of one job are processed in COB / partition order so partial progress is useful.

### 7.4 RBAC and quotas

- OIDC group → role mapping in config:
  - `viewer`: catalog and job status.
  - `requester`: submit/cancel own jobs for allowed datasets/tables and targets.
  - `operator`: cancel any job, pause/resume continuous jobs, view guard details.
  - `admin`: global pause, config reload, rekey jobs.
- Quotas per group: max bytes per job, max bytes per day, max concurrent jobs, max COB dates per request. Over-quota requests are rejected with a clear message (an approval workflow is a later phase, see PLAN).

### 7.5 REST API (v1)

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/catalog` | Datasets, tables, targets the caller may use. |
| `POST /api/v1/estimates` | Metadata-only estimate for a would-be job. |
| `POST /api/v1/jobs` | Submit an ad-hoc job. Body: `{dataset|tables, cob: {dates|from,to}, target, mode}`. Returns job id. Idempotency key header supported. |
| `GET /api/v1/jobs?mine=true&state=…` | List jobs. |
| `GET /api/v1/jobs/{id}` | Job detail: state, wait reason, queue position, progress, ETA. |
| `GET /api/v1/jobs/{id}/chunks` | Chunk-level detail. |
| `POST /api/v1/jobs/{id}/cancel` | Cancel. |
| `GET /api/v1/guard` | Current guard state per source cluster/host, active blocking reasons (no query text). |
| `GET /api/v1/continuous` | Continuous jobs: last success, lag vs SLO, state. |
| `POST /api/v1/continuous/{name}/pause` / `resume` | Operator. |
| `POST /api/v1/admin/pause` / `resume` | Admin global kill switch. |
| `POST /api/v1/admin/config/reload` | Admin; validates then activates a new config version. |

OpenAPI spec is generated and published with the build. The portal uses only this API.

### 7.6 Later: restricted custom filters

Power users may later supply an extra filter (`WHERE region = 'EU'`) in addition to the COB.
It would be parsed into a restricted AST (column comparisons, IN, AND/OR, literals only;
no functions/subqueries), checked against `EXPLAIN ESTIMATE`, and still subject to
`force_index_by_date`. Not in v1.

---

## 8. Continuous (near-real-time) mode

Tables listed under `continuous:` in config are kept in sync by scheduled polls. Every
poll is a normal job and goes through the Prod Guard; when prod is busy, lag grows and
catches up later. The lag SLO is a target, not a guarantee.

### 8.1 `append_watermark`

For append-mostly tables with a monotonic insertion column (e.g. `inserted_at`):

- Every `interval` (e.g. 60 s), read rows with `watermark_column > last_watermark − overlap` from partitions whose `max(modification_time)` changed since the last poll.
- `overlap` (e.g. 10 min) covers late-arriving rows. Duplicates are removed by re-assembling the affected partitions via the staging/REPLACE path (§5.4), or, for very large partitions, by a `ReplacingMergeTree` target keyed on the sort key (configurable `dedupe: replace_partition | replacing_merge_tree`).
- The new watermark is the max value read, stored in Postgres per (table, target) after the REPLACE commits.
- `only_recent_partitions: N` limits polling to the newest N partitions, so continuous mode never turns into an unplanned backfill; older history is copied with ad-hoc or scheduled jobs.

### 8.2 `partition_fingerprint`

For tables that are mutated, re-loaded, or have no usable watermark:

- Every `interval`, compute per-partition fingerprints from `system.parts` (metadata only): `(sum(rows), sum(bytes_on_disk), max(modification_time), hash(sorted active part names))`.
- Partitions whose fingerprint differs from the last copied one are re-copied in full via staging/REPLACE. Dropped partitions on prod are dropped on the target (`sync_drops: true`, default) — deliberately, to mirror retention.
- After copy, the fingerprint is re-read; if it changed during the copy the partition is re-queued (ensures convergence under concurrent prod writes).
- Merges change part names without changing data; to avoid needless re-copies, a partition whose `rows` and logical content checksum are unchanged is skipped. The logical checksum is optional (`verify: checksum`) because it costs a read (`SELECT sum(cityHash64(*))` per partition through the guard).

### 8.3 `scheduled_full_refresh`

Cron-driven full copy of a table or dataset (e.g. reference data nightly at 03:00 in a
low-traffic window), optionally restricted to the last N partitions.

### 8.4 Monitoring

Per (table, target): last successful sync time, lag, rows copied, pre-emptions, current
wait reason. Alerts when lag exceeds `lag_slo` for longer than `lag_alert_after`.

---

## 9. Configuration

### 9.1 Format and lifecycle

- One YAML file (or a directory of YAML files merged in lexical order), kept in git and changed through code review. Secrets are references (`${vault:path#key}` or `${env:NAME}`), never literals.
- Validated by JSON Schema plus semantic checks (§9.3) in CI (`clickhouse-repl validate-config`) and at load.
- Hot reload: `POST /api/v1/admin/config/reload` or SIGHUP; a new immutable version is activated only if valid. Running jobs keep the version they started with; each job records its config version.

A complete, annotated example is in [`docs/examples/replicator.yaml`](examples/replicator.yaml).

### 9.2 Top-level sections

| Section | Contents |
|---------|----------|
| `sources` | Prod clusters: shards, read replicas, watched hosts, credentials references. |
| `targets` | Non-prod clusters: hosts, cluster name, ZooKeeper path template, environment marker, database mapping. |
| `guard` | Poll interval, thresholds, stable window, concurrency, blackout windows, pre-emption, circuit breaker. |
| `masking` | Realms, key references, domains (strategy + options), dictionaries. |
| `tables` | Table specs: source, COB column, column policies, `mv_mode`, overrides, chunk sizing. |
| `datasets` | Named groups of tables. |
| `continuous` | Continuous jobs: table/dataset, targets, mode, interval, SLO. |
| `scheduling` | Queue weights and per-user concurrency caps. |
| `access` | OIDC group → role, allowed datasets/tables/targets, quotas. |

### 9.3 Semantic validation (fail closed)

- Every column of every table spec has a policy, or the table is `non_pii` with approver.
- No masked column appears in the partition key expression.
- Every table has a COB column or partition expression usable with `force_index_by_date` (or is `full_copy` with a size cap).
- Domains referenced exist; `fpe_ff1` domains are explicitly approved.
- No target host/alias resolves to an address in any source cluster (prod) — **hard error**.
- Every target declares an environment marker and none is `prod`.
- Continuous intervals ≥ guard `stable_window`; chunk sizes within L1 limits.
- (Online, `validate-config --online`) the declared schema matches prod's `system.columns`.

---

## 10. Security

| Area | Control |
|------|---------|
| Prod access | `repl_reader` (read-only, limited grants, L1 limits, source IP restricted); `repl_guard` (system tables + KILL own-user queries). Credentials in Vault, rotated. |
| Target access | `repl_writer` per target with rights only on managed databases and `repl_meta`. |
| Target safety | Host deny-list built from all prod hosts (resolved IPs); marker check `SELECT value FROM repl_meta.environment` must equal the configured target name before **every** write. |
| Masking keys | Only in worker memory; fetched from Vault/KMS; never logged, never sent to ClickHouse; audit records version only. |
| Data at rest on workers | None (streaming). If spill-to-disk is ever enabled, it is encrypted with an ephemeral key and the masked data only. |
| Transport | TLS to prod, targets, Postgres, Vault; portal over HTTPS. |
| Portal | OIDC, CSRF protection, RBAC on every API call, no query text or raw prod data shown. |
| Audit | Append-only audit table: requester, job, tables, partitions, COB, target, rows, bytes, config version, key version, guard decisions. Exportable to SIEM. |
| Supply chain | Pinned dependencies, SBOM, image scanning in CI. |

---

## 11. Observability and operations

- **Metrics** (Prometheus): `chrepl_guard_state{cluster,host}`, `chrepl_guard_block_reason_total{reason}`, `chrepl_preemptions_total`, `chrepl_chunk_duration_seconds`, `chrepl_rows_copied_total{table,target}`, `chrepl_bytes_read_total{cluster}`, `chrepl_queue_depth{queue}`, `chrepl_wait_seconds{queue}`, `chrepl_continuous_lag_seconds{table,target}`, `chrepl_masking_rows_total{domain}`.
- **Alerts**: guard UNKNOWN > 5 min; circuit breaker open; continuous lag > SLO; chunk failure rate; any L1 limit hit (planner bug); orphan staging tables.
- **Traces**: one trace per chunk (plan → token wait → read → mask → insert → replace).
- **Runbook** (PLAN M7): pausing, draining, rekey, adding a table, handling drift, recovering a stuck job.
- **Retention on targets**: optional per-target TTL or janitor to drop COB partitions older than N days, to stop QA clusters growing unbounded.

---

## 12. Failure modes

| Failure | Behaviour |
|---------|-----------|
| Worker crashes mid-chunk | Lease expires (default 60 s); chunk re-queued; staging table truncated on retry. |
| Prod becomes busy mid-chunk | Pre-emption (§4.5); chunk re-queued with backoff; no partial data on target. |
| Guard leader dies | Tokens expire within lease; standby acquires advisory lock; state UNKNOWN (→ no reads) until first successful poll round. |
| Prod host unreachable / failover | Guard UNKNOWN → RED; reads stop; resume when healthy. Read replica re-selected if the preferred one is gone. |
| Target down / out of disk | Chunk fails with transient error, retried with backoff; job FAILED after attempts exhausted; nothing written to prod. |
| Schema drift on prod | Job BLOCKED (`SCHEMA_DRIFT`/`UNCLASSIFIED_COLUMN`) until config updated. |
| Partition dropped by TTL during copy | Chunk read returns 0 rows / partition missing → partition marked `SOURCE_GONE`; continuous mode drops it on target if `sync_drops`. |
| Very wide rows / huge strings | Chunk sizing uses bytes, not rows; bounded buffers; `max_memory_usage` L1 cap. |
| Guard flapping | Hysteresis + stable window; circuit breaker on repeated pre-emption. |
| Postgres unavailable | No new tokens, workers stop at next heartbeat (fail closed); in-flight reads cancelled. |
| Masking key unavailable | Workers refuse to start chunks (fail closed). |
| Same partition targeted by two jobs | Advisory lock serialises the REPLACE; later job re-assembles from current target state. |

---

## 13. Open questions for the reviewer

1. **Read replicas**: can each prod shard get a replica excluded from production load balancing? This is the single biggest reducer of residual impact.
2. **ClickHouse versions** of prod and targets — determines workload scheduling DDL (§4.2) and some settings availability.
3. **Prod topology**: sharded or single shard? Replicated? Tiered/S3 storage?
4. **Masking key custody**: who owns the Vault/KMS keys and approves rotation and `fpe_ff1` domains?
5. **Targets**: shared QA environments, or per-team/per-user database namespaces (e.g. `trading_qa_alice`)? The design supports a `database_template` but quotas and cleanup differ.
6. **Approvals**: are large ad-hoc requests self-service within quota, or do they need a lead's approval?
7. **Guard thresholds**: who signs off the defaults in §4.4, and what is the impact budget for the M7 load test?
8. **Blackout windows**: EOD/month-end schedules to pre-configure.
9. **Retention** of copied data on targets.
10. **Notifications**: email, Slack, or portal only.
