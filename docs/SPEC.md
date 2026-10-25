# clickhouse-repl — Design Specification

Status: **Approved for implementation (rev 3)** · Owner / sign-off: repository owner · Last updated: 2026-10-21

`clickhouse-repl` copies data from the production ClickHouse estate into one or more
non-production ClickHouse environments (QA, UAT, perf). It masks sensitive columns
consistently on the way, keeps a configured set of tables up to date continuously, and lets
QA engineers request ad-hoc copies (a table or a dataset for a COB date) from a
self-service portal.

The overriding requirement is that **replication must never impact production
services**. The design meets it by reading **only from the passive DR datacentre**, never
connecting to the active production datacentre, and protecting the DR side's own job —
staying in sync and ready to take over — with a guard that defers to replication and
stops immediately if DR becomes active.

### Review decisions (rev 2 and rev 3)

| Topic | Decision |
|-------|----------|
| Topology | Two DCs: **active (prod)** and **passive (DR)**. Each DC runs **3 shards × 1 replica**. DR replicas are ordinary `ReplicatedMergeTree` replicas of the same tables (parts fetched via Keeper). |
| Read source | **DR DC only.** The tool never opens a connection to the active DC — not for data, metadata, or monitoring. |
| ClickHouse version | **25.8** today, upgrade to **26.8** planned. CI tests against both. |
| Masking keys | v1: a **static key** supplied as a secret. Later: **Fortanix DSM (BYOK)** key provider. |
| Approvals | **None.** Self-service within quotas, since reads cannot reach production services. |
| Targets | **Shared environments** (e.g. `qa1`, `uat`). No per-user/per-team namespaces. |
| Thresholds & impact budget | Signed off by the repository owner. |
| DR access storage (rev 3) | **Local** (`user_directories` has no replicated storage), so users can be provisioned with SQL on each DR node. |
| DC-role signal (rev 3) | **No external endpoint.** DR_ACTIVE detection uses foreign activity on DR and the operator failover flag. |

---

## 1. Goals and non-goals

### 1.1 Goals

| # | Goal |
|---|------|
| G1 | Copy a configured list of databases / tables from the production estate (via its DR replicas) to one or more non-prod clusters. |
| G2 | Mask configured columns **consistently**: the same input value in the same masking *domain* always produces the same output, across tables, jobs and runs, so joins and referential integrity survive masking. |
| G3 | **Continuous mode**: a configuration-defined set of tables is kept near-real-time in sync (seconds to minutes of lag). |
| G4 | **Ad-hoc mode**: a QA user selects a table or a predefined dataset and one or more COB dates in a portal, submits, and the job runs in the background with visible progress. |
| G5 | **Production protection**: reads happen only on the passive DR DC; the active DC receives **zero connections**; on DR, replication is subordinate to DR replication health and stops within seconds if DR becomes active; everything fails closed. |
| G6 | Auditability: every copy is attributable (who, what, when, which masking key version, which config version, how fresh the DR data was). |
| G7 | Safe to retry: every unit of work is idempotent; crashes and pre-emptions lose time, never correctness. |

### 1.2 Non-goals (v1)

- Writing anything to production or DR (data, schema, or settings). Source users are read-only.
- Any connection to the active DC.
- Sub-second change data capture. ClickHouse has no native CDC; v1 is polling-based (§8).
- Non-ClickHouse targets.
- Per-user or per-team target namespaces; approval workflows.
- Arbitrary free-form SQL extracts by end users (a restricted filter may come later, §7.6).
- Reversible "un-masking" for end users.

### 1.3 What "no impact on production" means here

**Active DC (serves production traffic):** the tool never connects to it. This is enforced
three ways: config validation rejects any source or target host that is, or resolves into,
the configured active-DC deny-list (§9.3); the Kubernetes NetworkPolicy for all tool pods
allows egress only to DR hosts, targets, Postgres and the key store; and an integration test
proves no `repl_*` user ever appears in the active side's `system.query_log` (PLAN M3).
Production queries therefore cannot be slowed by the tool's reads, and a heavy production
query never competes with replication — which covers the original requirement "if a huge
query runs in prod, wait": the two never share a server.

**Passive DR DC:** it serves no queries, but it has two production duties: staying in sync
(its replication lag is the RPO) and being ready for failover. Reads on DR compete with
replication fetches and merges for disk, CPU and network on the same node (each shard has
only one DR replica). So on DR the tool:

1. is bounded by **limits enforced inside ClickHouse** (user profile, quota, workload scheduling) that put replication fetches/merges ahead of it (§4.2);
2. **starts a read only** when DR replication is healthy and caught up and the node is quiet, sustained over a window (§4.4) — so pressure on prod that slows DR replication (e.g. a heavy ingest or a big mutation in prod) automatically holds replication;
3. is **pre-empted within seconds** when DR replication falls behind or DR shows any sign of becoming active (§4.5, §4.6);
4. **fails closed** when DR state is unknown.

**Impact budget** (acceptance criterion, values to be signed off by the repository owner,
verified by the M7 load test): *zero connections to the active DC*, and *with replication
running at full allowed concurrency, DR `absolute_delay` p99 increases by no more than
B seconds (proposed B = 5 s) and never exceeds the guard threshold for longer than one
pre-emption cycle*.

---

## 2. Terminology

| Term | Meaning |
|------|---------|
| **Active DC** | The datacentre serving production traffic. Never contacted by the tool. |
| **Passive DC / DR** | The standby datacentre whose replicas receive data via `ReplicatedMergeTree` replication. The tool's only source. |
| **Source** | The DR cluster (3 shards × 1 replica). Only ever read. |
| **Target** | A shared non-production ClickHouse environment (e.g. `qa1`, `uat`). Written by the tool. |
| **Table spec** | Config entry describing one source table: COB column, column masking policies, continuous settings. |
| **Dataset** | A named group of table specs that are usually copied together (e.g. `trading-eod`). |
| **COB date** | Close-of-business / business date. Each table spec maps COB to a column (e.g. `trade_date`, `cob_date`). |
| **Job** | A user- or scheduler-initiated request (ad-hoc copy, continuous poll, scheduled refresh). |
| **Chunk** | The unit of work: one source shard × one table × one partition × (optional) sort-key range × (optional) COB filter. |
| **Masking domain** | A named value space (e.g. `customer_id`, `email`). Consistency is guaranteed within a domain. |
| **Realm** | A masking key scope (e.g. `nonprod`). All targets in one realm see identical masked values. |
| **Source Guard** | The component that decides whether DR may be read *now*, and kills reads when it may not. |

---

## 3. Architecture

### 3.1 Components

```
  ACTIVE DC (prod) ── never contacted ──╳                       ┌──────────── PASSIVE DC (DR) ────────────┐
        │                                                       │  dr-s1      dr-s2      dr-s3             │
        └── ReplicatedMergeTree fetches (Keeper) ──────────────►│  (1 replica per shard)                   │
                                                                └───────▲───────────────▲─────────────────┘
                                                                        │ signal polls  │ bounded reads
 ┌──────────────────────────── Control plane (Kotlin / Spring Boot) ────┼───────────────┼───────────┐
 │ QA ─► Portal (React) ─► REST API ─► Job service ─► Planner ──────────┘               │           │
 │   OIDC                  ▲  CLI        ▲                                               │           │
 │                         │             │ Scheduler (continuous)                        │           │
 │                Config loader     Source Guard (per process): DR signals, DC-role,     │           │
 │                                  tokens, pre-emption ──────────────────────────────────┘           │
 │                                          │                                                        │
 │                              Postgres (jobs, chunks, leases, watermarks, audit, guard state)      │
 └──────────────────────────────────────────┼────────────────────────────────────────────────────────┘
                                            │ admission tokens / kill signals
 ┌──────────── Workers (same binary, role=worker, N replicas) ─────────────────────────────┐
 │ claim chunk ─► stream SELECT from DR shard replica (RowBinary) ─► mask in JVM            │
 │            ─► INSERT into target staging table ─► REPLACE PARTITION on target            │
 └──────────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|-----------|----------------|
| **Config loader** | Loads and validates YAML config (§9), resolves secrets, exposes an immutable versioned snapshot. |
| **REST API** | Catalog, estimates, job submission/cancel/status, guard status, continuous status, admin operations (§7.5). |
| **Portal** | React + TypeScript SPA behind OIDC. Thin client of the REST API. |
| **Job service** | Validates requests (RBAC, quotas, policy completeness), creates jobs, coalesces duplicates. |
| **Planner** | Turns a job into chunks per DR shard using **metadata only** (`system.parts`, `EXPLAIN ESTIMATE`). Never scans data. |
| **Scheduler** | Creates continuous-mode and scheduled-refresh jobs on their intervals (§8). |
| **Source Guard** | Runs inside every process that touches DR (workers, and the API for estimates). Polls DR hosts, runs the DC-role check, computes GREEN/AMBER/RED, admits reads and pre-empts them (§4). Cluster-wide limits (`concurrency.global`, `per_host`) are leased read tokens in Postgres; the pause and failover flags are shared in Postgres. (M4 decision, replacing a single elected leader: each process gates and pre-empts its own reads directly, with no cross-process kill path and no leader failover gap. The cost is one small poll per DR host per process per `poll_interval`.) |
| **Workers** | Claim chunks from the Postgres queue, obtain an admission token, stream → mask → write, heartbeat, finalise. |
| **Postgres** | System of record for jobs, chunks, leases, watermarks, partition fingerprints, guard state history, "last copied" registry, audit log. |
| **CLI** | `validate-config`, `dry-run` (plan + estimate, no data read), `submit`, `status`, `cancel`. |

The control plane and workers are the **same artefact** started with different roles
(`api`, `worker`, `scheduler` from M5, or `all` for local/dev). Postgres is the only
stateful dependency; there is no message broker.

### 3.2 Technology choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language / runtime | Kotlin 2.x on JDK 21 (virtual threads) | Team preference (JVM); good streaming IO, mature crypto. |
| Framework | Spring Boot 4.1 (Web MVC, OAuth2 resource server, Actuator), springdoc for OpenAPI | OIDC, metrics and ops endpoints out of the box. Boot 3.x reached end of OSS support in 2026. Jackson 3 at the HTTP layer; the domain modules use Jackson 2. |
| ClickHouse client | Own minimal HTTP client (JDK `HttpClient`, never proxied) and RowBinary codec (`modules/clickhouse`) | Exact control of the value model, streaming reads and inserts, typed query parameters, per-query settings, `query_id` and cancellation; no dependency on a driver's type mapping. Chosen over `client-v2` in M2. |
| ClickHouse versions | 25.8 (current), 26.8 (planned) | Both in the CI test matrix. |
| State | PostgreSQL 15+, Flyway migrations, plain JDBC (`modules/control`) | `FOR UPDATE SKIP LOCKED` queue, advisory locks, transactional audit. A thin JDBC helper replaced jOOQ: the queries are few, hand-tuned and Postgres-specific. |
| Crypto | JCA HMAC-SHA256 / HKDF, Bouncy Castle (FF1 FPE) | Standard, FIPS-friendly primitives. |
| Key management | v1 static key (secret file/env); later Fortanix DSM (BYOK) | §6.2. |
| Config | YAML + JSON Schema, Jackson Kotlin module | Reviewable in git, validated in CI. |
| Portal | React 18 + TypeScript + Vite + TanStack Query | Simple SPA; served by the control plane. |
| Observability | Micrometer → Prometheus, OpenTelemetry traces, structured JSON logs | |
| Tests | JUnit, kotest-property, Docker-based integration tests (`modules/it`, driven through the `docker` CLI) against real ClickHouse 25.8 and 26.8, Playwright | Real servers in tests; no Testcontainers dependency (Docker Engine 29 compatibility). |
| Deployment | Container image, Kubernetes (Helm chart) with strict egress NetworkPolicy | 2× api/scheduler/guard, N× workers. |

---

## 4. Production protection — Source Guard

Defence in depth on the DR side, on top of the absolute rule that the active DC is never
contacted. **Every layer fails closed.**

### 4.1 Overview

| Layer | Where enforced | What it does |
|-------|---------------|--------------|
| L0 | Config + network | The active DC is unreachable for the tool: config deny-list, DNS re-check, NetworkPolicy. |
| L1 | Inside DR ClickHouse | Hard limits on the replication users; workload scheduling ranks replication fetches/merges above our reads. |
| L2 | Topology | Each shard is read from its single DR replica, local tables only, one read per host at a time. |
| L3 | Guard admission | A chunk may start only when DR replication is healthy and the node is quiet for a stable window. |
| L4 | Guard pre-emption | Running chunks are killed when DR replication falls behind or the node gets busy. |
| L5 | DC-role check | Any sign that DR has become active turns the guard RED and kills all reads. |
| L6 | Operations | Blackout windows (e.g. DR drills), global kill switch, failover flag, circuit breaker. |

### 4.2 L1 — limits enforced by ClickHouse (DR nodes only)

Two dedicated users on **DR nodes only**, provisioned by the DBAs from files shipped with
the tool (`deploy/dr-users.d/*.xml` and `deploy/dr-ddl.sql`). The tool never runs DDL on
the source.

> **Do not let these users or workloads reach the active DC.**
> - DR uses **local** access storage (confirmed), so the SQL below is safe when run on each DR node individually. Had `user_directories` included `replicated` (Keeper-backed) storage, a `CREATE USER` on a DR node would replicate to prod; the `users.d` XML variant stays in `deploy/` for config-management use.
> - Never run this DDL `ON CLUSTER` — the cluster definition may include active-DC replicas.
> - Workload/resource DDL is stored locally unless `workload_zookeeper_path` is configured; if it is, apply workload settings via DR-only server config instead.
> - M3 includes a check (`validate-config --online`) that reads `system.user_directories` and refuses to proceed if replicated access storage ever appears (e.g. after a config change on DR).

Settings profile and quota (SQL form shown for readability; the XML form in `deploy/` is equivalent):

```sql
-- Bounds for replication reads. CONST / MAX constraints mean the client can only make
-- things *more* restrictive, never less.
CREATE SETTINGS PROFILE repl_reader_profile SETTINGS
    readonly = 2,                                   -- no writes; settings changes allowed but bounded below
    max_threads = 2 MAX 2,
    max_memory_usage = 4000000000 MAX 4000000000,   -- 4 GB per query
    max_execution_time = 900 MAX 900,               -- chunks are sized well below this
    max_bytes_to_read = 50000000000 MAX 50000000000,
    max_network_bandwidth = 50000000 MAX 50000000,  -- 50 MB/s per query (native protocol only — not HTTP)
    max_local_read_bandwidth = 50000000 MAX 50000000,  -- disk-read throttle; the one that bounds HTTP reads
    max_network_bandwidth_for_user = 100000000 MAX 100000000,
    max_concurrent_queries_for_user = 2 MAX 2,
    use_uncompressed_cache = 0 CONST,
    min_bytes_to_use_direct_io = 10000000 MAX 10000000,   -- large reads bypass the OS page cache
    enable_filesystem_cache = 0 CONST,              -- tiered/S3 storage: do not fill the FS cache
    read_from_filesystem_cache_if_exists_otherwise_bypass_cache = 1 CONST,
    force_index_by_date = 1,                        -- reject reads that cannot prune by partition/minmax (see notes)
    workload = 'replication_reads' CONST;

CREATE USER repl_reader IDENTIFIED WITH sha256_password BY '…'
    HOST IP '10.20.0.0/16'                          -- worker egress range only
    SETTINGS PROFILE repl_reader_profile;
GRANT SELECT ON trading.*, crm.*, refdata.* TO repl_reader;   -- exactly the configured databases
GRANT SELECT(database, table, partition, partition_id, name, rows, bytes_on_disk,
             modification_time, active, min_date, max_date) ON system.parts TO repl_reader;

CREATE QUOTA repl_reader_quota FOR INTERVAL 1 hour
    MAX queries = 2000, read_rows = 20000000000, read_bytes = 2000000000000, execution_time = 7200
    TO repl_reader;

-- Guard user: reads system tables and can kill replication queries only.
-- No readonly for the guard: readonly forbids KILL QUERY on other users' queries (found in M3 testing).
CREATE USER repl_guard IDENTIFIED WITH sha256_password BY '…' HOST IP '10.20.0.0/16'
    SETTINGS max_threads = 1, max_execution_time = 10, max_memory_usage = 200000000;
GRANT SELECT ON system.processes, system.metrics, system.events, system.asynchronous_metrics,
               system.merges, system.mutations, system.replication_queue, system.replicas,
               system.replicated_fetches
    TO repl_guard;
GRANT KILL QUERY ON *.* TO repl_guard;  -- used only with WHERE user = 'repl_reader' (enforced in code + tested)
-- system.replicas / system.replication_queue only list tables the user may SHOW (found in M3 testing):
GRANT SHOW TABLES ON *.* TO repl_guard;
```

Workload scheduling on DR (ClickHouse 25.8 SQL workloads; exact syntax and the set of
supported workload settings are verified against 25.8 and 26.8 images in M3):

```sql
CREATE RESOURCE disk_io (READ DISK default, WRITE DISK default);
CREATE RESOURCE cpu (MASTER THREAD, WORKER THREAD);

CREATE WORKLOAD all;
CREATE WORKLOAD background IN all SETTINGS weight = 9;              -- merges / mutations
CREATE WORKLOAD replication_reads IN all SETTINGS weight = 1,
    max_bytes_per_second = 50000000, max_concurrent_threads = 2;
-- Server config on DR nodes: <merge_workload>background</merge_workload>,
--                            <mutation_workload>background</mutation_workload>
```

Notes:

- With workload scheduling in place, when merges/mutations and our reads contend for disk
  or CPU on a DR node, the server itself gives ~90 % to background work. Replication
  *fetches* are protected primarily by the guard (L3/L4), since fetch traffic is not
  necessarily covered by the same workload classes.
- **`force_index_by_date = 1`** makes ClickHouse refuse a replication query that cannot
  prune by the partition key / minmax index, so an accidental full-table scan of a large
  fact table is refused by the server. Table specs must therefore have a COB column or
  partition filter usable for pruning; this is checked at config validation (§9.3) and
  verified per table in integration tests. The only exception is `full_copy` tables
  (unpartitioned reference data) below a configured size cap, for which the worker relaxes
  this one setting per query; all other L1 limits still apply.
- Chunk sizing (§5.3) keeps normal chunks far below these limits, so hitting an L1 limit
  indicates a planner bug and fails the chunk (it is not retried blindly).

### 4.3 L2 — read source

- The source is the DR cluster: **3 shards, each with exactly one DR replica**. Every chunk
  is read from its shard's DR replica, using that shard's **local** tables. The tool never
  reads through a `Distributed` table (which could route to active-DC replicas and would
  fan out across shards).
- Because the DR replica is the only copy of that shard in the DR DC, it is never "spare":
  it also performs fetches and merges. Default concurrency is therefore **1 read per DR host**
  and **2 cluster-wide** (§4.4).
- Freshness: the guard records each DR replica's `absolute_delay` at chunk start; jobs show
  "data as of ≈ T − delay". A replica whose delay exceeds `max_replica_delay` is not read
  (it is catching up — reading would slow it further and return stale data).

### 4.4 L3 — admission control

Each guard instance polls **each DR host directly** (never the active DC, never via
`clusterAllReplicas`) every `poll_interval` (default 5 s), with small fixed queries against
in-memory system tables. An unreachable DR host is an explicit UNKNOWN signal.

**Signals and default thresholds** (configurable; all evaluated per DR host):

| Group | Signal | Source | Blocks when |
|-------|--------|--------|-------------|
| Replication | Replica delay | `system.replicas.absolute_delay` (max over tables) | `> 30 s` |
| Replication | Queue size | `system.replicas.queue_size` (sum) / `system.replication_queue` | `> 50` entries |
| Replication | Oldest queue entry | `min(create_time)` in `system.replication_queue` | older than `120 s` |
| Replication | Stuck entries | `system.replication_queue` with `num_tries > 3` or `last_exception != ''` | any |
| Replication | Fetches in flight | `count()` from `system.replicated_fetches` | `> 4` |
| Replication | Keeper session | `system.replicas.is_readonly` / `is_session_expired` | any table |
| Host | CPU utilisation | `system.asynchronous_metrics` `OSUserTimeNormalized` + `OSSystemTimeNormalized` | `> 0.50` |
| Host | IO wait | `OSIOWaitTimeNormalized` | `> 0.20` |
| Host | Memory | `MemoryTracking` vs `max_server_memory_usage` | `> 0.60` |
| Background | Merges / mutations | `system.merges` count; `system.mutations` not done | `> 10` merges or any mutation `> 50 GB` |
| DC role | Foreign activity | §4.6 | any → `DR_ACTIVE` |
| Ops | Blackout / pause / failover flag / circuit | config, Postgres | active |

Because a heavy ingest or mutation in the active DC shows up on DR as fetch backlog,
queue growth and merges, these signals make replication yield to production indirectly —
without ever touching the active DC.

**Guard states** (per host, and aggregated per source cluster):

| State | Meaning | Allowed |
|-------|---------|---------|
| **GREEN** | All signals below threshold continuously for `stable_window` (default 60 s). | Data reads (chunks), metadata queries. |
| **AMBER** | Below threshold but not yet stable, or within 80–100 % of a threshold. | Cheap metadata queries only (`system.parts`, `EXPLAIN ESTIMATE`). No new chunks. |
| **RED** | A threshold exceeded, blackout, kill switch, failover flag, circuit open. | Only the guard's own polls. Running chunks pre-empted. |
| **DR_ACTIVE** | DR looks active (§4.6). A RED sub-state with its own release rule. | As RED; requires explicit confirmation to leave. |
| **UNKNOWN** | Host unreachable, poll failed, poll stale (> 3× interval), clock skew, or no replicated table visible to the guard user (a missing grant must never look like a healthy queue). | **Treated as RED** (fail closed). |

**Hysteresis**: leaving RED requires `stable_window` of continuously clean samples, with
signals below `release_ratio × threshold` (default 0.8).

**Admission tokens**: in GREEN the guard grants tokens up to `concurrency.global` (default
2) and `concurrency.per_host` (default 1). A worker must hold a token (a Postgres row with a
short lease) to issue a data read. Tokens are revoked when the host or cluster leaves GREEN.

**Wait reasons** are recorded on each waiting chunk and shown to users, e.g. *"Waiting for
DR shard 2: replication delay 74 s (threshold 30 s)"* or *"Paused: DR failover flag set by
ops"*. No query text is ever shown.

### 4.5 L4 — pre-emption

- While any chunk runs, the guard keeps polling. When the chunk's host (or the cluster)
  leaves GREEN, the guard:
  1. revokes the chunk's token,
  2. cancels the read by closing the HTTP connection (the client sets `cancel_http_readonly_queries_on_client_close = 1`), and
  3. issues `KILL QUERY WHERE user = 'repl_reader' AND query_id = '<chunk query id>' ASYNC` as a backstop after `grace` (default 5 s).
- Workers tag every source query with a deterministic `query_id`
  (`chrepl-<job>-<chunk>-<attempt>`), so kills are precise and auditable. The guard refuses
  to kill any query whose user is not `repl_reader` (checked in code, covered by tests).
- A pre-empted chunk returns to the queue as `PREEMPTED` with exponential backoff. Writes
  go to a per-chunk staging table (§5.4), so nothing partial is visible on the target and
  nothing is lost.
- Pre-emption latency target: **≤ poll_interval + 5 s**.

### 4.6 L5 — DC-role check ("DR became active")

During a failover DR starts serving production. From that moment the tool must not read
from it at all. Any of the following turns the guard **DR_ACTIVE** immediately, revokes all
tokens and pre-empts all running chunks:

1. **Foreign activity on a DR host**: any query in `system.processes` from a user that is not
   `repl_reader`, `repl_guard`, or a configured `ignored_users` entry (e.g. a monitoring
   user); or any increase of `system.events` `InsertQuery` between polls (the tool never
   inserts on DR; replicated parts arrive via fetches, which don't count as insert queries).
2. **Failover flag**: `POST /api/v1/admin/failover` (operator/admin) — one step of the DC
   failover runbook. Persisted in Postgres.

There is no external, authoritative "active DC" signal in this deployment (rev 3), so
detection rests on (1) and (2). Consequently **setting the failover flag is the first step
of the DC failover runbook**, before traffic is moved; (1) is the safety net if that step is
missed. An external role check can be added later as a third trigger if such a signal
appears.

Leaving DR_ACTIVE requires an operator to clear it (`POST /api/v1/admin/failover/clear`)
after the DCs are back in their normal roles, followed by the usual `stable_window`. An
automatically detected DR_ACTIVE never clears itself.

If the roles are swapped long-term (the old prod becomes the new passive side), the
config's source and deny-list are changed through normal review; the tool does not follow
role changes automatically.

### 4.7 L6 — operational controls

- **Blackout windows**: cron + duration (e.g. DR drills, month-end catch-up windows). Guard is RED inside them.
- **Global kill switch**: `POST /api/v1/admin/pause` (admin) or a config flag; persisted, survives restarts.
- **Circuit breaker**: more than `N` pre-emptions within `T` (default 5 in 30 min) keeps the guard RED for `open_for` (default 1 h) and alerts.
- **No override**: there is deliberately **no** API to force a job through a RED guard. Admins can only pause, never bypass.

### 4.8 Guard self-impact

≈ 8 small queries per DR host per 5 s against in-memory system tables, by `repl_guard`
with `max_threads = 1` and a 10 s timeout. Measured in the M7 load test as part of the
impact budget. Zero queries to the active DC.

---

## 5. Data path

### 5.1 Why stream through the worker

Considered and rejected: `INSERT INTO target … SELECT … FROM remoteSecure(dr, …)` run on
the target. It is fast, but masking would have to be expressed in SQL (keys in
`system.query_log` on both sides), the read would bypass the guard's tokens and kill
control, and source credentials would live on non-prod servers.

Chosen: workers stream rows from the DR replica in `RowBinaryWithNamesAndTypes`, mask in the
JVM, and stream into the target with `INSERT … FORMAT RowBinary`. Masking keys live only in
worker memory. Raw source data is never written to worker disk; buffers are bounded and
backpressure flows from target insert to source read.

Details settled in M2:

- Strings that are not valid UTF-8 are carried as raw bytes, so pass-through columns are copied byte for byte; masking such a value fails the chunk.
- `JSON` columns travel as strings (`output_format_binary_write_json_as_string` / `input_format_binary_read_json_as_string`).
- A query that fails mid-stream aborts the chunked HTTP response; the client reports it as an error, never as a short result.
- All markers of a target are checked before anything is written to any of its hosts.
- `table_overrides.ttl` is applied to newly created target tables when every column it references exists.

### 5.2 Schema synchronisation

1. Read `SHOW CREATE TABLE db.t` and `system.columns` from one DR replica (all shards share the schema; the planner checks the other two for drift).
2. Rewrite for the target:
   - `ON CLUSTER` name → target cluster name;
   - `Replicated*MergeTree('/clickhouse/tables/{shard}/…', '{replica}')` → target ZooKeeper path template (or a non-replicated engine for single-node targets);
   - storage policy / TTL / settings overrides from config (e.g. shorter TTL on QA);
   - `Distributed` tables recreated pointing at the target cluster;
   - masked columns keep their type (masking is type-preserving, §6.4).
3. Create database/table on the target if missing.
4. **Drift detection**: if source and target columns differ, or the source has a column with
   no masking policy, the job **blocks** (`SCHEMA_DRIFT` / `UNCLASSIFIED_COLUMN`) until config is
   updated. Target-side drift is fixed by the tool with `ALTER TABLE` on the target — targets are
   disposable; the source is never altered.
5. Materialized views (per table spec, `mv_mode`):
   - `base_only` (default): copy the base table; target MVs compute their own targets. MV target tables are excluded from copying.
   - `targets_only`: copy MV target tables directly; MVs on the target are created but their source tables are not copied.
   - `none`: MVs are not created on the target.

### 5.3 Planning chunks (metadata only, per DR shard)

For each of the 3 DR shard replicas:

```sql
SELECT partition_id, partition, sum(rows) AS rows, sum(bytes_on_disk) AS bytes,
       max(modification_time) AS mtime, count() AS parts,
       groupArray(name) AS part_names
FROM system.parts
WHERE active AND database = {db:String} AND table = {t:String}
GROUP BY partition_id, partition
```

- COB filters are mapped to partitions using the partition key expression (e.g. `toYYYYMM(trade_date)`) and, where the COB column is in the minmax index, `min_date/max_date`; `EXPLAIN ESTIMATE` gives rows/marks for the filtered read.
- A chunk targets ≤ `chunk.max_bytes` (default 2 GiB compressed) and ≤ `chunk.max_seconds` (default 120 s estimated at the per-query bandwidth cap). Larger partitions are split into **sort-key ranges** derived from the primary index (the `mergeTreeIndex()` table function, available in 25.8). Planning reads index marks, never column data. Ranges are on the sort key, not on part names, so they stay valid while parts merge.
- Every data read is of the form:
  ```sql
  SELECT <columns> FROM db.t                -- local table on the DR shard replica
  WHERE _partition_id = {pid:String}
    [AND <cob_column> IN ({cob:Array(Date)})]
    [AND <sort_key_range>]
  SETTINGS max_threads = …, …              -- within L1 bounds
  ```
- Chunks of one job are interleaved across shards so that at most one read per DR host runs at a time and all three shards progress evenly.

### 5.4 Writing: idempotent and atomic

For each source partition `P` (per shard) being copied to target table `T`:

1. For each chunk `c` of `P`: create (or truncate) `T__stg_<job>_<c>` (`CREATE TABLE … AS T`, non-replicated), stream masked rows into it. On retry it is truncated first → idempotent.
2. When all chunks of `P` (across all source shards that map to the same target shard) are done, assemble `T__stg_<job>_P`:
   - **Partition-aligned copy** (whole partition requested, or the COB filter covers the whole partition): `ALTER TABLE T__stg_P ATTACH PARTITION ID 'P' FROM T__stg_<job>_<c>` for each chunk.
   - **Sub-partition copy** (e.g. one COB date inside a monthly partition): first copy the *other* rows of `P` already present on the target into the staging table (`INSERT INTO T__stg_P SELECT * FROM T WHERE _partition_id = 'P' AND NOT (<cob filter>)` — target-side work only), then attach chunk staging partitions.
3. `ALTER TABLE T REPLACE PARTITION ID 'P' FROM T__stg_P` on the target shard — atomic swap. Target readers see either the old or the new partition, never a mix.
4. Drop staging tables. A janitor drops orphaned `__stg_` tables older than 24 h.

Constraints and validation:

- Columns used in the **partition key expression must not be masked** (the partition id would change). Validation error.
- Masked columns in the sort key are allowed (data is re-sorted on insert).
- Concurrent writers to the same `(target, table, partition)` are serialised by a Postgres advisory lock held during step 3.
- Consistency is **per partition, per read**: a partition split into several chunks is not a point-in-time snapshot if it changes mid-copy. Continuous mode (§8.2) re-checks fingerprints and re-copies if needed.

### 5.5 Shard mapping (source has 3 shards)

| Target topology | `shard_mapping` | Behaviour |
|-----------------|-----------------|-----------|
| 3 shards | `same_shards` (default when shard counts match) | DR shard *i* → target shard *i*; staging and REPLACE run on target shard *i*. Data distribution mirrors prod. |
| Different shard count | `distributed` | Rows are inserted into staging on the shard chosen by the target's sharding key, computed in the worker; REPLACE per target shard. **Not implemented yet** (needs the sharding expression evaluated in the worker); jobs with this mapping are refused. |
| Single node | `single` | All three source shards' chunks land in one staging table per partition; one REPLACE. |

If a **masked column is part of the sharding key**, `same_shards` keeps rows on their
original shard index, which no longer matches where the target's `Distributed` table would
route the masked value (affects shard-local joins and `optimize_skip_unused_shards`).
Validation warns and recommends `distributed` mapping for such tables.

---

## 6. Masking

### 6.1 Policy model

Every column of every configured table must have an explicit policy:

- `pass` — copied as-is (an explicit statement that the column is not sensitive), or
- a masking rule referencing a **domain** (and optionally a strategy override).

A table may set `unlisted_columns: pass` only together with `classification: non_pii` and
an `approved_by` field (e.g. reference data). Otherwise **unlisted columns are an error** —
at config validation for known columns, and at runtime (`UNCLASSIFIED_COLUMN`) for columns
added to prod later. This is the main defence against new PII silently leaking to non-prod.

An optional heuristic **PII scanner** (`validate-config --scan-pii`) flags `pass` columns
whose names match patterns (`email`, `phone`, `name`, `iban`, `ssn`, `dob`, `address`, …)
for reviewer attention. It is advisory only.

### 6.2 Consistency and keys

- All deterministic strategies derive their randomness from
  `HMAC-SHA256(K[realm][version][domain], canonical(value))`, where
  `K[realm][version][domain] = HKDF-SHA256(master_key[realm][version], info = domain)`.
- `canonical(value)` is a type-aware encoding (integers as decimal strings, dates as ISO-8601,
  strings as UTF-8 NFC; optional per-domain normalisation: trim, lower-case). Because integers
  and strings canonicalise to the same text, a `customer_id` stored as `UInt64` in one table and
  as `String` in another masks consistently when both reference domain `customer_id`.
- **Same realm + same domain + same key version ⇒ same output**, across tables, jobs, runs and
  targets. Different realms produce unrelated outputs.
- Workers load the master key at start-up into memory, never log it, and never send it to
  ClickHouse. The audit log records the key version used for each chunk.

**Key providers** (behind one `MasterKeyProvider` interface):

| Provider | Milestone | Behaviour |
|----------|-----------|-----------|
| `static` | v1 (M1) | A ≥ 256-bit random key (hex or base64) read from a secret reference — a mounted Kubernetes Secret file (`${file:/var/run/secrets/…}`) or an env var. A literal key in YAML is a validation error. The config declares its `version`. |
| `fortanix` | Later (see PLAN) | Fortanix DSM, BYOK. Envelope model: a DSM-held, non-exportable KEK wraps the HMAC master key; the wrapped blob and key version are stored with the config/secret; workers authenticate to DSM (app API key or client certificate) and **unwrap once at start-up**; derivation and per-value HMAC stay local. Per-value DSM calls are not used (throughput). If the HMAC key is instead held as an exportable DSM security object, the provider exports it at start-up under the same rules. |

- **Key rotation** changes every masked value and breaks consistency with data already on
  targets. It is a planned event: bump the version, then run a `rekey` job (full re-copy of
  affected tables). Mixed versions in one target table are prevented: the target records the
  key version per table in `repl_meta.tables`, and writes with a different version are refused
  except by `rekey`.
- Moving from `static` to `fortanix` **without** rotating keeps masked values identical, as
  long as the same key material is imported into DSM (BYOK) and the version is unchanged.

### 6.3 Strategies

| Strategy | Deterministic | Output | Typical use |
|----------|---------------|--------|-------------|
| `hash` | yes | hex / base64 of HMAC, optionally truncated to N chars | opaque identifiers where format doesn't matter |
| `tokenize` | yes | format-preserving: digits → digits, letters → letters (case kept), separators and length kept | account numbers, phone numbers, IDs |
| `fpe_ff1` | yes, **bijective** | NIST FF1 format-preserving encryption (§6.3.1) | identifiers that must stay **unique** (keys) — reversible by key holders, so restricted to approved domains |
| `email` | yes | `tokenize(local)@<domain-mode>`; domain kept, mapped to `example.com`, or tokenized | emails |
| `dictionary` | yes | value from a versioned dictionary, index = HMAC mod size | names, cities, company names |
| `date_shift` | yes (per entity) | date ± offset; offset = HMAC(entity value) mod (2·max_days+1) − max_days | dates of birth, event dates — intervals within one entity preserved |
| `numeric_noise` | yes (per entity or per value) | value × (1 ± p), rounded to the column's scale, clamped to type range | amounts, balances where distribution matters |
| `partial` | n/a | keep first/last N characters, replace the rest with a mask char | display values |
| `null` | n/a | NULL (Nullable) or type default | free text, notes |
| `constant` | n/a | fixed literal | values that must exist but carry no meaning |

#### 6.3.1 Strategy details (as implemented in M1)

- **`tokenize`**: a keystream is derived from HMAC(domain key, canonical value), so equal inputs give equal outputs. Per character: ASCII digits stay digits and the first digit of a digit run stays non-zero if it was non-zero (a leading `0` stays `0`); ASCII letters keep their case; other letters (é, Ж, …) become ASCII letters of the same case, so non-Latin names do not leak through unchanged; separators, spaces and punctuation are kept. Integers are tokenized through their decimal text (sign kept), so an integer and its decimal string mask identically.
- **`fpe_ff1`** (NIST SP 800-38G FF1, AES-256 key derived per domain, empty tweak):
  - `alphabet: digits` requires `width: W` (6–19). Values are non-negative integers below 10^W, from integer columns or digit-only strings, encrypted as a W-digit block; the output is an integer below 10^W. Integer and string columns therefore agree (string outputs carry no leading zeros). The column type must hold 10^W − 1 (e.g. W ≤ 9 for UInt32), checked when the table masker is compiled. Negative values, non-digit strings and values ≥ 10^W fail the chunk.
  - Other alphabets (`alphanumeric`, `hex`, …) encrypt the string over the alphabet, keeping its length; every character must be in the alphabet and the length must reach the NIST minimum domain (radix^length ≥ 10^6, e.g. 4 characters for alphanumeric).
- **`email`**: split at the last `@`; the local part is tokenized (or hashed to 12 hex characters) seeded by the whole normalised address; the domain is kept, mapped to `example.com`, or tokenized label-wise keeping the TLD. Strings without a usable `@` are tokenized whole.
- **`hash`**: HMAC as hex or URL-safe base64, truncated to `length` (and to a FixedString's length); on integer columns the HMAC is reduced into the type range; on UUID columns its first 16 bytes form the UUID.
- **`date_shift` / `numeric_noise`**: seeded by the entity column's original (unmasked) value, or by the value itself when no entity column is set or the entity is NULL. Results are clamped to the type range (e.g. Date 1970-01-01…2149-06-06).
- **JSON (`json_paths` and `JSON` columns)**: paths are `$.a.b`, `$.a[*].b` or `$.a[3]`; a policy on an object or array applies to every scalar beneath it, and `default` covers everything else. String leaves are masked as `String`, integer leaves as integers, floating leaves as `Float64`; booleans are kept. A value that is not valid JSON fails the chunk. Output JSON is re-serialised compactly (key order kept).
- **Normalisation** (`normalise: [trim, lower, nfc]`) is applied to string inputs before masking; canonical text is always NFC.

`tokenize` and `hash` are not bijective: collisions are possible on small value spaces. For
columns that must remain unique, use `fpe_ff1`, or enable `collision_check: true` on the domain
(the worker counts distinct inputs vs outputs per chunk and fails the chunk on a collision; per
chunk, not global).

Strategies that need **another column of the same row** (`date_shift`, entity-seeded
`numeric_noise`) declare `entity_column`; the entity value's canonical form seeds the
derivation, so all dates of the same customer shift by the same offset everywhere.

### 6.4 Types

- Supported: all integer widths, Decimal, Float, String, FixedString, Date/Date32/DateTime/DateTime64, UUID, IPv4/IPv6, Enum (pass or `constant` only), and wrappers `Nullable`, `LowCardinality`, `Array(T)`, `Map(K, V)` (mask keys and/or values), `Tuple(...)` (per element), the `JSON` type and JSON-in-String via path selectors (`$.contact.email`).
- Masking is **type-preserving**: output fits the column type (length for FixedString, range for integers, scale for Decimal). Integer `tokenize` keeps the digit count and a non-zero leading digit; if the result would overflow the type it is re-derived up to 8 times and only then reduced modulo the type's range (a rare, documented loss of cross-type consistency near the type's maximum).
- The engine works on plain JVM values (e.g. `UInt64` as `BigInteger`, `Date` as `LocalDate`, `Map` as an ordered map); the mapping is documented on `chrepl.masking.types.ChType` and the transfer layer (M2) converts rows accordingly.
- Column types are checked against strategies when a table masker is compiled from the real schema; every incompatibility, unclassified column or missing column is reported at once and blocks the job.
- NULL in → NULL out (unless strategy is `constant`).

### 6.5 Performance

- Masking is CPU-bound in the worker, never on ClickHouse.
- Per-domain LRU caches (`value → masked`) exploit the high repetition of IDs in fact tables.
- Target throughput per worker: ≥ 50 MB/s uncompressed for typical tables. The L1 bandwidth cap is the bottleneck by design.
- Measured in M1 (JMH, one thread, trades-like rows of ~185 bytes from the example config): **≈ 258 MB/s** with realistic repetition of customers/accounts (LRU cache hits); **≈ 20 MB/s** in the worst case where every masked value is distinct and caching is off, dominated by FF1 (≈ 200k values/s per core, AES-bound). A fact table whose FF1-masked key never repeats therefore needs 2–3 cores per 50 MB/s read; other strategies run at 1–3 M values/s.

---

## 7. Ad-hoc mode and portal

### 7.1 User flow

1. QA signs in (OIDC SSO). The portal shows the **catalog** filtered by RBAC: datasets, tables, and the shared target environments they may write to.
2. QA picks a **dataset** (e.g. `trading-eod`) or a single **table**, then **COB date(s)**: a single date, a range, or a list. `full_copy` tables (reference data) ignore COB.
3. QA picks a **target environment** (e.g. `qa1`) and mode: `replace` (default; replace the selected COB slice) or `skip_if_present`.
4. The portal shows an **estimate** (`POST /api/v1/estimates`): partitions, rows, compressed bytes per table from DR metadata; current guard state; queue depth; and, because targets are shared, the **"last copied" registry** for the selected slice — e.g. *"`trading.trades` COB 2026-10-19 on qa1 was last replaced by alice 2 h ago (key v1, config v14)"*. Replacing a slice copied by someone else within the last `recent_copy_warning` (default 24 h) requires an explicit confirmation tick; it is never blocked.
5. QA submits. Quotas are checked (§7.4); **no approval step**. The job appears in "My jobs" with state, **queue position**, **wait reason** when waiting on DR, per-table and per-shard progress, data freshness (DR delay at read time) and an ETA. QA can **cancel**.
6. On completion the user is notified in the portal (optional webhook for email/Slack), with a summary and the masking key version used.

### 7.2 Job lifecycle

```
PENDING ──plan──► QUEUED ──token──► RUNNING ──all chunks done──► FINALISING ──► SUCCEEDED
   │                │ ▲               │  │
   │                │ └──PREEMPTED────┘  └──error (retries exhausted)──► FAILED
   │                ▼
   │         WAITING_FOR_SOURCE (guard not GREEN; reason shown)
   └──────── BLOCKED (schema drift / unclassified column / quota) ──► needs config change or user action
any state ──cancel──► CANCELLING ──► CANCELLED (running reads killed, staging dropped)
```

Chunk states mirror this (`QUEUED`, `RUNNING`, `PREEMPTED`, `DONE`, `FAILED`). Up to 5 attempts
with exponential backoff for transient errors; pre-emptions do not count as failed attempts.

### 7.3 Scheduling, fairness and shared targets

- Two queues: `continuous` and `adhoc`, served by weighted fair share (default 3:1) when the guard grants tokens.
- Within `adhoc`: per-user and per-group concurrency caps (default 2 running jobs per user), FIFO otherwise.
- **Coalescing**: an identical request (same table, COB, target) already queued or running is merged; the second requester is attached to the existing job and notified with it.
- **Overlapping requests** for the same slice with different options are serialised per `(target, table, partition)`; the later one runs after the earlier one finishes.
- Chunks of one job are processed in COB / partition order, interleaved across DR shards.
- The **"last copied" registry** (Postgres, mirrored to `repl_meta.copy_history` on each target) records, per (target, table, partition/COB): job, requester, time, rows, key version, config version, DR delay at read time.

### 7.4 RBAC and quotas

- OIDC group → role mapping in config:
  - `viewer`: catalog, job status, last-copied registry.
  - `requester`: submit/cancel own jobs for allowed datasets/tables and targets.
  - `operator`: cancel any job, pause/resume continuous jobs, set/clear the failover flag, view guard details.
  - `admin`: global pause, config reload, rekey jobs.
- Quotas per group: max bytes per job, max bytes per day, max concurrent jobs, max COB dates per request. Over-quota requests are rejected with a clear message.

### 7.5 REST API (v1)

| Method & path | Purpose |
|---------------|---------|
| `GET /api/v1/catalog` | Datasets, tables, targets the caller may use. |
| `POST /api/v1/estimates` | Metadata-only estimate plus last-copied info for a would-be job. |
| `POST /api/v1/jobs` | Submit an ad-hoc job. Body: `{dataset|tables, cob: {dates|from,to}, target, mode, confirm_overwrite_recent}`. Idempotency key header supported. |
| `GET /api/v1/jobs?mine=true&state=…` | List jobs. |
| `GET /api/v1/jobs/{id}` | Job detail: state, wait reason, queue position, progress, freshness, ETA. |
| `GET /api/v1/jobs/{id}/chunks` | Chunk-level detail. |
| `POST /api/v1/jobs/{id}/cancel` | Cancel. |
| `GET /api/v1/targets/{target}/history?table=…&cob=…` | Last-copied registry. |
| `GET /api/v1/guard` | Guard state per DR host, active blocking reasons (no query text). |
| `GET /api/v1/continuous` | Continuous jobs: last success, lag vs SLO, state. |
| `POST /api/v1/continuous/{name}/pause` / `resume` | Operator. |
| `POST /api/v1/admin/failover` / `failover/clear` | Operator: DC failover flag (§4.6). |
| `POST /api/v1/admin/pause` / `resume` | Admin global kill switch. |
| `POST /api/v1/admin/config/reload` | Admin; validates then activates a new config version. |

An OpenAPI spec is generated and published with the build. The portal uses only this API.

Implementation notes (M4):

- JSON uses snake_case names; null fields are omitted. Dates are ISO-8601.
- `cob` is `{"dates": [...]}` or an inclusive calendar range `{"from": ..., "to": ...}` (at most 366 days, then the quota's `max_cob_dates_per_job`). Dates without data are no-ops, or empty the target slice with `replace`.
- Errors are `{"error": code, "message": ...}`: 400 `bad_request`, 401 (no or invalid token), 403 `forbidden`, 404 `not_found`, 409 `confirm_overwrite_recent` (recent copy by someone else; resubmit with `confirm_overwrite_recent: true`), 422 `quota`.
- `Idempotency-Key` header: a repeat from the same user returns the original job.
- `GET /api/v1/me` returns the caller's user, groups and effective roles (portal and CLI use it).
- The pause and failover flags also stop workers from planning or starting chunks, even for unguarded `dc: test` sources. Planning waits while the guard is not GREEN or AMBER; such jobs show the guard's reason as `wait_reason` while PENDING.
- The active config version lives in Postgres, so a reload through any API replica applies to every process within 5 s. Source and guard settings are read at startup, so changing them needs a restart.
- `/api/v1/continuous*` arrive with M5.

### 7.6 Later: restricted custom filters

Power users may later supply an extra filter (`WHERE region = 'EU'`) in addition to the COB,
parsed into a restricted AST (column comparisons, IN, AND/OR, literals only), checked with
`EXPLAIN ESTIMATE`, and still subject to `force_index_by_date`. Not in v1.

---

## 8. Continuous (near-real-time) mode

Tables listed under `continuous:` are kept in sync by scheduled polls. Every poll is a
normal job and goes through the Source Guard; when DR replication is busy, lag grows and
catches up later. The lag SLO is a target, not a guarantee. Lag is measured end to end
(active DC → DR replication delay + DR → target copy).

### 8.1 `append_watermark`

For append-mostly tables with a monotonic insertion column (e.g. `inserted_at`):

- Every `interval`, per DR shard, read rows with `watermark_column > last_watermark − overlap` from partitions whose `max(modification_time)` changed since the last poll.
- `overlap` (e.g. 10 min) covers late-arriving rows and DR replication delay. Duplicates are removed by re-assembling the affected partitions via the staging/REPLACE path (§5.4), or, for very large partitions, by a `ReplacingMergeTree` target keyed on the sort key (`dedupe: replace_partition | replacing_merge_tree`).
- The new watermark (per table, target and shard) is the max value read, stored in Postgres after the REPLACE commits.
- `only_recent_partitions: N` limits polling to the newest N partitions, so continuous mode never turns into an unplanned backfill; older history is copied with ad-hoc or scheduled jobs.

### 8.2 `partition_fingerprint`

For tables that are mutated, re-loaded, or have no usable watermark:

- Every `interval`, compute per-shard, per-partition fingerprints from `system.parts` (metadata only): `(sum(rows), sum(bytes_on_disk), max(modification_time), hash(sorted active part names))`.
- Partitions whose fingerprint differs from the last copied one are re-copied in full via staging/REPLACE. Partitions dropped at the source are dropped on the target (`sync_drops: true`, default).
- After copy, the fingerprint is re-read; if it changed during the copy the partition is re-queued (convergence under concurrent writes).
- Merges change part names without changing data; to avoid needless re-copies, a partition whose `rows` and logical checksum are unchanged is skipped. The logical checksum is optional (`verify: checksum`) because it costs a read (`SELECT sum(cityHash64(*))` per partition, through the guard).

### 8.3 `scheduled_full_refresh`

Cron-driven full copy of a table or dataset (e.g. reference data nightly at 03:00),
optionally restricted to the last N partitions.

### 8.3.1 Implementation notes (M5)

- **Scheduler.** One leader across all `scheduler` processes, elected with a Postgres advisory lock held on a dedicated connection.
- **Streams.** Each (continuous entry, table, target) is a stream with state in `continuous_state`. A poll:
  - reads DR metadata only while the guard is GREEN or AMBER, and not while paused or failed over;
  - submits a whole-partition job (`jobs.partitions`) to the `continuous` queue;
  - is applied (fingerprints, watermarks, `synced_as_of`) only after that job SUCCEEDED, so a failed or cancelled copy is retried by the next poll.
- **Failed polls count as polls.** The next attempt waits for `interval`. In testing, a tight retry loop exhausted the reader's hourly query quota within minutes, and the DR quota stopped it as designed.
- **Metadata fingerprint.** Per shard and partition: `sum(rows) / max(max_block_number) / max(mutation version)` from `system.parts`.
  - It changes on inserts, mutations and deletes, but not on merges (checked on 25.8).
  - `data_version` is not usable because plain merges change it.
  - `verify: checksum` adds a guarded `count() ‖ sum(cityHash64(*))` read per changed partition, and skips a re-copy when the content is unchanged.
- **`append_watermark`.**
  - Only partitions whose fingerprint changed are read.
  - The watermark query (guarded, per shard and per partition) finds the partitions that have rows with `watermark_column > last − overlap`; those are re-assembled through staging/REPLACE, so overlap duplicates cannot occur.
  - Rows stamped earlier than `last − overlap` are not picked up (late beyond overlap).
  - Partitions dropped at the source are not dropped on the target in this mode; use `partition_fingerprint` with `sync_drops` for that.
- **Whole-partition reads of COB tables** carry the partition's own min/max date on `cob_column`. `_partition_id = …` alone does not satisfy the reader profile's `force_index_by_date` (found in M5 testing).
- **`scheduled_full_refresh`** fires when the cron matches a minute since the last run, in the entry's time zone. After an outage longer than two days it catches up with a single run. A `full_copy` table is copied whole. With `only_recent_partitions`, the newest N partitions are copied. Otherwise every source partition is copied, and target partitions missing at the source are emptied.
- **Lag** is `now − synced_as_of`. `synced_as_of` is the time of the last poll that left the target in sync, minus DR's replica delay at that poll, so it includes the DR replication component. `sloBreached` means lag > `lag_slo`; `alerting` means lag > `lag_slo + lag_alert_after`. Both are exposed as `chrepl_continuous_lag_seconds` and `chrepl_continuous_alerting` on `/actuator/prometheus`.
- **Not implemented:** `dedupe: replacing_merge_tree`. Config validation rejects it.
- **Recent-copy confirmation.** Ad-hoc requests do not need it for slices last written by the scheduler.

### 8.4 Monitoring

Per (table, target): last successful sync, end-to-end lag, DR replication delay component,
rows copied, pre-emptions, current wait reason. Alerts when lag exceeds `lag_slo` for longer
than `lag_alert_after`.

---

## 9. Configuration

### 9.1 Format and lifecycle

- One YAML file (or a directory of YAML files merged in lexical order), kept in git and changed through code review. Secrets are references (`${file:…}`, `${env:…}`, later `${fortanix:…}`), never literals.
- Validated by JSON Schema plus semantic checks (§9.3) in CI (`clickhouse-repl validate-config`) and at load.
- Hot reload: `POST /api/v1/admin/config/reload` or SIGHUP; a new immutable version is activated only if valid. Running jobs keep the version they started with; each job records its config version.

A complete, annotated example is in [`docs/examples/replicator.yaml`](examples/replicator.yaml).

### 9.2 Top-level sections

| Section | Contents |
|---------|----------|
| `active_dc` | Deny-list of active-DC hostnames and CIDRs that the tool must never contact. |
| `sources` | The DR cluster: `dc: dr`, shards with their DR replica, DC-role check, credential references. |
| `targets` | Shared non-prod environments: hosts, cluster name, shard mapping, ZooKeeper path template, environment marker, table overrides. |
| `guard` | Poll interval, thresholds, stable window, concurrency, blackout windows, pre-emption, circuit breaker. |
| `masking` | Realm, key provider, domains (strategy + options), dictionaries. |
| `tables` | Table specs: source, COB column, declared `partition_key` / `sharding_key` expressions, column policies, `mv_mode`, overrides, chunk sizing. |
| `datasets` | Named groups of tables. |
| `continuous` | Continuous jobs: table, targets, mode, interval, SLO. |
| `scheduling` | Queue weights and per-user concurrency caps. |
| `access` | OIDC group → role, allowed datasets/tables/targets, quotas. |

### 9.3 Semantic validation (fail closed)

- **Active-DC isolation**: every source host is declared under a source with `dc: dr`; no source or target host is listed in, or resolves (DNS, checked at load and every 5 min at runtime) into, `active_dc.hosts` / `active_dc.cidrs` — **hard error**, and at runtime a resolution into the deny-list stops all reads and alerts.
- Every column of every table spec has a policy, or the table is `non_pii` with an approver.
- No masked column appears in the declared `partition_key` expression; masked `sharding_key` columns with `same_shards` targets produce a warning (§5.5). The COB column must be `pass` and should appear in the partition key (warning otherwise). The online check compares the declared keys with DR.
- Every table has a COB column or partition expression usable with `force_index_by_date` (or is `full_copy` with a size cap).
- Domains referenced exist; `fpe_ff1` domains are explicitly approved.
- Masking key is a reference (not a literal) and decodes to ≥ 256 bits.
- Every target declares an environment marker, none is `prod`/`dr`.
- Continuous intervals ≥ guard `stable_window`; chunk sizes within L1 limits; pre-emption cannot be disabled.
- (Online, `validate-config --online`) declared schema matches DR `system.columns` on all shards; DR access storage is local (§4.2); L1 profile is in effect for `repl_reader`.

---

## 10. Security

| Area | Control |
|------|---------|
| Active DC | Never contacted: config deny-list + runtime DNS check + NetworkPolicy + IT proof (§1.3). |
| DR access | `repl_reader` (read-only, limited grants, L1 limits, source IP restricted); `repl_guard` (system tables + KILL own-user queries). Users provisioned on DR nodes only, via local access storage. Credentials in a secret store, rotated. |
| Target access | `repl_writer` per target with rights only on managed databases and `repl_meta`. |
| Target safety | Marker check `SELECT value FROM repl_meta.environment` must equal the configured target name before **every** write. |
| Masking keys | Only in worker memory; static key from a mounted secret (v1), Fortanix DSM unwrap (later); never logged, never sent to ClickHouse; audit records version only. |
| Data at rest on workers | None (streaming). If spill-to-disk is ever enabled, it holds masked data only, encrypted with an ephemeral key. |
| Transport | TLS to DR, targets, Postgres, key store; portal over HTTPS. |
| Portal | OIDC, RBAC on every API call, no query text or raw source data shown. The API is stateless and accepts bearer tokens only (no cookies), so CSRF does not apply; the SPA keeps its token in memory. |
| Audit | Append-only audit table: requester, job, tables, partitions, COB, target, rows, bytes, config version, key version, DR delay, guard decisions. Exportable to SIEM. |
| Supply chain | Pinned dependencies, SBOM, image scanning in CI. |

---

## 11. Observability and operations

- **Metrics** (Prometheus): `chrepl_guard_state{host}`, `chrepl_guard_block_reason_total{reason}`, `chrepl_dr_replica_delay_seconds{host}`, `chrepl_dr_replication_queue{host}`, `chrepl_preemptions_total`, `chrepl_chunk_duration_seconds`, `chrepl_rows_copied_total{table,target}`, `chrepl_bytes_read_total{host}`, `chrepl_queue_depth{queue}`, `chrepl_wait_seconds{queue}`, `chrepl_continuous_lag_seconds{table,target}`, `chrepl_masking_rows_total{domain}`, `chrepl_active_dc_violations_total` (must stay 0).
- **Alerts**: DR_ACTIVE detected; guard UNKNOWN > 5 min; circuit breaker open; continuous lag > SLO; chunk failure rate; any L1 limit hit (planner bug); orphan staging tables; any active-DC violation.
- **Traces**: one trace per chunk (plan → token wait → read → mask → insert → replace).
- **Runbook** (PLAN M7): pausing, draining, **DC failover/failback steps** (set/clear failover flag, swap config), rekey, adding a table, handling drift, recovering a stuck job.
- **Retention on targets**: optional per-target TTL or janitor to drop COB partitions older than N days.

---

## 12. Failure modes

| Failure | Behaviour |
|---------|-----------|
| **DC failover — DR becomes active** | Foreign activity or the failover flag → DR_ACTIVE within one poll; all tokens revoked; running reads killed; stays stopped until an operator clears the flag (§4.6). |
| DR replication falls behind (prod ingest spike, network) | Replication signals breach → RED; running chunks pre-empted; resume after delay recovers + `stable_window`. |
| Keeper session lost on DR / replica readonly | `is_readonly` / `is_session_expired` → RED. |
| Active DC down (DR not yet promoted) | DR fetches fail → replication-queue errors → RED. |
| Worker crashes mid-chunk | Lease expires (default 60 s); chunk re-queued; staging table truncated on retry. |
| Worker process dies | Its read tokens and chunk leases expire; other workers take the chunks over. A restarted process's guard starts UNKNOWN (no reads) until its first successful poll round. |
| DR host unreachable | That host UNKNOWN → its shard blocked; with `fail_closed_scope: cluster` (default) all reads stop. |
| Target down / out of disk | Chunk fails with transient error, retried with backoff; job FAILED after attempts exhausted. |
| Schema drift at source | Job BLOCKED (`SCHEMA_DRIFT` / `UNCLASSIFIED_COLUMN`) until config updated. |
| Partition dropped by TTL during copy | Partition marked `SOURCE_GONE`; continuous mode drops it on the target if `sync_drops`. |
| Very wide rows / huge strings | Chunk sizing uses bytes, not rows; bounded buffers; `max_memory_usage` L1 cap. |
| Guard flapping | Hysteresis + stable window; circuit breaker on repeated pre-emption. |
| Postgres unavailable | No new tokens; workers stop at the next heartbeat (fail closed); in-flight reads cancelled. |
| Masking key unavailable (secret missing, DSM unreachable) | Workers refuse to start chunks (fail closed). |
| Two jobs target the same partition | Advisory lock serialises the REPLACE; the later job re-assembles from the current target state. |
| DR hostname re-resolves into the active-DC deny-list | All reads stop, alert raised (`chrepl_active_dc_violations_total`). |

---

## 13. Decisions and open questions

### 13.1 Resolved

| # | Question | Decision |
|---|----------|----------|
| 1 | Read replicas | Read only from the passive DR DC (3 shards × 1 replica, ReplicatedMergeTree). |
| 2 | ClickHouse versions / topology | 25.8 now, 26.8 soon; active/passive DCs; 3 shards × 1 replica per DC. |
| 3 | Masking key custody | Fortanix DSM (BYOK) eventually; static key for v1. |
| 4 | Approvals | None; self-service within quotas, reads limited to DR. |
| 5 | Guard access to active DC | None. DR-only polling. |
| 6 | Target namespaces | Shared environments only. |
| 7 | Threshold / impact-budget sign-off | Repository owner. |
| 8 | DR access storage (rev 3) | Local. SQL provisioning per DR node; never `ON CLUSTER`. |
| 9 | External DC-role signal (rev 3) | None available. Failover flag is runbook step 1; foreign-activity detection as safety net. |

### 13.2 Still open

1. **Impact budget values**: confirm B (proposed 5 s p99 increase in DR `absolute_delay`) and the default thresholds in §4.4.
2. **Monitoring users on DR** that must be listed in `ignored_users` so they don't trigger DR_ACTIVE.
3. **Network path DR → non-prod targets**: bandwidth available, any cross-DC links that must not be saturated (affects `max_network_bandwidth_for_user`).
4. **Fortanix DSM**: wrap/unwrap (non-exportable KEK) vs exportable HMAC key; authentication method for workers.
5. **Blackout windows**: DR drill schedule, month-end windows.
6. **Retention** of copied data on shared targets.
7. **Notifications**: portal only, or also email/Slack.
8. **Target topology**: shard counts of `qa1`, `uat`, etc. (drives `shard_mapping`).
