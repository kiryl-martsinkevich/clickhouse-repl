# clickhouse-repl — Implementation Plan

Status: **In progress (rev 3)** · Companion to [SPEC.md](SPEC.md)

This plan delivers the design in [SPEC.md](SPEC.md) in milestones that are each shippable
and testable on their own. The order puts the **masking engine** and the **Source Guard** —
the parts that protect data and production — ahead of convenience features, and nothing
reads real DR data until the guard milestone (M3) has passed its tests. The tool never
connects to the active production DC at any milestone.

Rev 2: source is the passive DR DC only (3 shards × 1 replica, ReplicatedMergeTree);
ClickHouse 25.8 and 26.8; static masking key in v1, Fortanix DSM later; shared target
environments; no approval workflow; sharded-source support moved from M7 into M2.
Rev 3: DR access storage is local; no external DC-role signal (failover flag + foreign-activity
detection only).

---

## 1. Repository layout

Gradle (Kotlin DSL) multi-module build, JDK 21, Kotlin 2.x.

```
clickhouse-repl/
├── build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml
├── modules/
│   ├── config/          # YAML model, JSON Schema, semantic validation (incl. active-DC deny-list), secret refs
│   ├── masking/         # strategies, key providers (static; fortanix later), HKDF, type codecs, policy checks
│   ├── clickhouse/      # client-v2 wrapper: streaming read/insert, settings, query_id, schema introspection
│   ├── guard/           # DR signal collectors, DC-role check, state machine, tokens, pre-emption, blackout, breaker
│   ├── transfer/        # schema sync, per-shard chunk planner, read→mask→staging→REPLACE, shard mapping, janitor
│   ├── jobs/            # Postgres job/chunk store, SKIP LOCKED queue, leases, scheduler, last-copied registry, audit
│   ├── app/             # Spring Boot app: REST API, OIDC/RBAC, roles (api|scheduler|guard|worker|all)
│   ├── cli/             # validate-config, dry-run, submit/status/cancel (REST client)
│   └── it/              # integration tests (Testcontainers topology below)
├── portal/              # React + TypeScript + Vite SPA (built into app's static resources)
├── deploy/
│   ├── dr-users.d/      # repl_reader / repl_guard users, profile, quota as XML (DR nodes only)
│   ├── dr-ddl.sql       # same in SQL (only for local access storage) + workload/resource DDL
│   ├── target-ddl.sql   # repl_writer, repl_meta.* on targets
│   └── helm/            # chart: api/scheduler/guard (2 replicas), workers (N), egress NetworkPolicy
└── docs/                # SPEC.md, PLAN.md, examples/, runbook.md (M7)
```

Module dependencies flow one way: `config` ← `masking` ← `transfer` → `clickhouse`,
`guard` → `clickhouse`, `jobs` → `config`, `app` → everything. `masking` and `guard` have no
Spring dependency, so they are unit-testable in isolation.

### 1.1 Integration-test topology

Docker containers driven through the `docker` CLI (`modules/it`, `./gradlew :it:test -Pit`), parameterised over **ClickHouse 25.8 and 26.8** (CI matrix). Each container is published on its own loopback address (127.0.0.x) so configs keep distinct hosts. M2 uses standalone source shards and targets; M3 adds:

- **Keeper** container.
- **"prod"** and **"DR"** ClickHouse containers holding `ReplicatedMergeTree` replicas of the same tables via that Keeper — 1 shard for most tests, a 3-shard × 2-replica variant (7 containers) for sharding tests in a nightly job.
- **Target** ClickHouse container(s): 1-shard and 3-shard variants.
- **Postgres**.
- A **load generator** that inserts into / mutates "prod" to create replication backlog on "DR", and can impersonate a foreign user on "DR" (failover simulation).

The tool under test is given **only DR and target addresses**; the "prod" container is listed in
`active_dc`.

## 2. Cross-cutting rules (apply to every milestone)

- **Never connect to the active DC.** Config validation rejects active-DC hosts/CIDRs; the Helm chart's NetworkPolicy allows egress only to DR hosts, targets, Postgres, OIDC and the key store; every IT asserts the "prod" container's `system.query_log` contains no `repl_*` user.
- **No test, demo or CI job ever connects to real production or DR clusters.**
- Every source query is built in one place (`clickhouse` module `SourceQueryBuilder`) that always attaches the `query_id`, the L1-compatible settings, and the `_partition_id` predicate. A unit test fails if any other code path issues `SELECT` against a source.
- Fail-closed defaults: unknown → deny, in config validation, masking policy lookup and the guard.
- Every PR: `./gradlew check` (ktlint, detekt, unit tests), integration tests on main (25.8 + 26.8), portal `npm run lint && npm test`.
- Security review sign-off at the end of M1 (masking) and M3 (guard); thresholds and impact budget signed off by the repository owner.

---

## 3. Milestones

Sizes are relative (S ≈ 1 week, M ≈ 2 weeks, L ≈ 3+ weeks for one engineer) and are for
sequencing, not commitments.

### M0 — Project skeleton and configuration (S) — ✅ done

Delivered: `modules/config` (model, loader, validator with stable issue codes) and `modules/cli`
(`validate-config`), CI workflow, 108 unit tests. Table specs gained declared `partition_key` /
`sharding_key` fields so the masked-key checks run offline.

Scope
- Gradle multi-module build, version catalog, ktlint, warnings-as-errors, GitHub Actions CI. (detekt is deferred until a release supports Kotlin 2.4; the ClickHouse 25.8/26.8 matrix starts with the first integration tests in M2.)
- `config` module: data model for all sections in SPEC §9.2, strict YAML binding (unknown fields and missing required fields are errors, reported with paths), `${file:…}` / `${env:…}` secret references. A JSON Schema for editor support is generated later, alongside the OpenAPI work in M4.
- Semantic validation from SPEC §9.3 (offline parts): **active-DC deny-list (hosts + CIDRs, DNS resolution)**, `dc: dr` sources, policy completeness, partition-key-not-masked, sharding-key warning, domain references, key is a reference, guard/continuous sanity.
- `cli validate-config <path>` with human-readable errors.
- `docs/examples/replicator.yaml` validates in CI.

Exit criteria
- Example config validates.
- Negative fixtures each fail with the expected error: missing column policy, masked partition key, source host in `active_dc.hosts`, target host resolving into `active_dc.cidrs`, source without `dc: dr`, literal masking key, unknown domain, pre-emption disabled.

### M1 — Masking engine (M) — ✅ done

Delivered: `modules/masking` — static key provider and HKDF key derivation, all ten strategies,
ClickHouse type parser and nested types (Nullable, LowCardinality, Array, Tuple, Map keys/values,
JSON paths and the JSON type), compiled per-table maskers with entity lookups, LRU cache and
per-chunk collision checks; 34 tests including property tests; JMH benchmarks. Throughput: ≈ 258 MB/s
per core on realistic trades-like rows, ≈ 20 MB/s worst case with every FF1 key distinct (see SPEC
§6.5). Config gained `width` for `fpe_ff1` over digits (SPEC §6.3.1). Formal security review of key
handling and strategies is still to be done by the reviewer.


Scope
- `MasterKeyProvider` interface; **`static` provider** (≥ 256-bit key from a secret file/env, versioned); test provider. Keys held in memory only, zeroed on shutdown, never logged (log-redaction test).
- HKDF per (realm, version, domain); canonicalisation per type; normalisers (trim, lower, NFC).
- Strategies: `hash`, `tokenize`, `fpe_ff1` (Bouncy Castle), `email`, `dictionary`, `date_shift`, `numeric_noise`, `partial`, `null`, `constant`.
- Type codecs: integers, Decimal, Float, String/FixedString, dates, UUID, IP, Enum, Nullable, LowCardinality, Array, Map (keys/values), Tuple, JSON type and JSON-in-String paths.
- Row masker compiled per table spec, entity-column lookups, per-domain LRU cache, collision check option.
- JMH micro-benchmarks per strategy.

Exit criteria
- Property tests (Kotest): determinism across instances and runs, realm and version separation, format preservation for `tokenize`/`fpe_ff1`, type-range safety, NULL handling, `fpe_ff1` bijectivity on sampled domains.
- Cross-table consistency: `customer_id` as `UInt64` in one table and `String` in another mask to the same value.
- Throughput ≥ 50 MB/s uncompressed per core on benchmark tables.
- Security review of key handling and strategies.

### M2 — Batch transfer via CLI, sharded source (L) — ✅ done

Delivered: `modules/types` (shared type model), `modules/clickhouse` (HTTP client and RowBinary codec
for every supported type), `modules/transfer` (schema sync with DDL rewriting, per-shard planner with
COB→partition mapping and primary-index range splits, chunk copy through a `ReadGate`, staging +
`REPLACE PARTITION`, marker and key-version checks, `repl_meta` bookkeeping, janitor), `cli copy`
(dc: test only), `deploy/target-ddl.sql`, and 12 Docker integration tests passing on 25.8 and 26.8.
Deviations: own HTTP client instead of `client-v2`; `shard_mapping: distributed` deferred (refused
for now); the "prod" container and its `query_log` assertion start with the M3 topology.


Scope
- `clickhouse` module: client-v2 wrapper with streaming `RowBinaryWithNamesAndTypes` read, streaming `RowBinary` insert, bounded pipe with backpressure, per-query settings, deterministic `query_id`, cancellation.
- Schema sync (SPEC §5.2): `SHOW CREATE TABLE` rewrite, create on target, drift detection across all DR shards → `SCHEMA_DRIFT` / `UNCLASSIFIED_COLUMN`.
- **Per-shard chunk planner** (SPEC §5.3): `system.parts` per DR shard replica, COB → partition mapping, `EXPLAIN ESTIMATE`, sort-key range splitting via `mergeTreeIndex()`, shard interleaving.
- Writer (SPEC §5.4): per-chunk staging, `ATTACH PARTITION FROM`, sub-partition merge, `REPLACE PARTITION`, staging cleanup, janitor; target environment marker check before every write; `repl_meta` tables (environment, table key versions, copy history).
- **Shard mapping** (SPEC §5.5): `same_shards`, `distributed`, `single`.
- `cli copy --table … --cob … --target …` end to end **without** the guard — only allowed when the source is declared `dc: test` (Testcontainers/dev); refused for `dc: dr`.

Exit criteria
- IT: copy a monthly-partitioned table for one COB date into a target that already has other dates in that month → only that date replaced, other dates intact, masked columns masked.
- IT: 3-shard DR → 3-shard target (`same_shards`) and → single-node target (`single`): row counts per shard and in total match the source.
- IT: re-run the same copy → identical target state (idempotency).
- IT: kill the worker mid-chunk → re-run completes, no duplicates.
- IT: a new column appears at the source → job blocks with `UNCLASSIFIED_COLUMN`.
- IT: Array/Map/Nullable/LowCardinality/JSON columns round-trip with masking.
- IT: target marker mismatch → write refused.
- IT (every test): "prod" container's `query_log` shows no `repl_*` user.

### M3 — Source Guard (L) — ✅ done

Delivered: `modules/guard` (per-host signal collection in one query, GREEN/AMBER/RED/DR_ACTIVE/UNKNOWN
state machine with stable window and release ratio, permits with global/per-host concurrency,
pre-emption by connection cancel plus a `KILL QUERY` backstop pinned to the reader and query id,
DR_ACTIVE on foreign queries or inserts, pause/failover flags, blackout windows via cron, circuit
breaker, wait reasons), `GuardGate` plugged into the copy pipeline (pre-empted chunks are retried),
`cli copy` guarded for `dc: dr`, `deploy/dr-ddl.sql` and `deploy/dr-users.d/chrepl.xml`. 15 state
machine tests and 8 integration tests on a Keeper + prod + DR `ReplicatedMergeTree` pair.

Found while testing against real servers (all fixed and covered by tests):
1. `system.replicas` / `system.replication_queue` only show tables the user may `SHOW`: without
   `GRANT SHOW TABLES ON *.*` the guard saw *no* backlog. The guard now also treats "no replicated
   tables visible" as UNKNOWN, so a missing grant fails closed.
2. `readonly` in the guard's profile forbids `KILL QUERY` on the reader's queries; the guard profile
   no longer sets it (its grants are limited to system-table reads and `KILL QUERY`).
3. `max_network_bandwidth` does not throttle HTTP reads; `max_local_read_bandwidth` does and is now
   part of the reader profile.

Not done in M3: the background-work share under workload scheduling is provisioned and asserted to
exist, but not yet measured (moved to the M7 load test).


Scope
- `deploy/dr-users.d/*.xml` and `deploy/dr-ddl.sql`: users, settings profile with constraints, quota, and **25.8 workload/resource DDL** (`CREATE RESOURCE` disk/cpu, `background` and `replication_reads` workloads, `merge_workload`/`mutation_workload` server settings). Verified against 25.8 and 26.8 images.
- `validate-config --online`: DR `system.user_directories` is local (or XML provisioning is used), the L1 profile is in effect for `repl_reader`, schema matches on all shards.
- DR signal collectors per host (SPEC §4.4): replication (delay, queue size/age, stuck entries, in-flight fetches, readonly/session), host (CPU, IO wait, memory), background (merges, mutations); direct per-host polling with timeouts; staleness → UNKNOWN.
- **DC-role check** (SPEC §4.6): foreign-activity detection (`system.processes` users, `InsertQuery` deltas, `ignored_users`), operator failover flag; DR_ACTIVE state with manual clear. (No external role endpoint exists — rev 3.)
- State machine GREEN/AMBER/RED/DR_ACTIVE/UNKNOWN with stable window and release ratio; per host and per cluster; `fail_closed_scope`.
- Admission tokens (Postgres-leased; in-memory for CLI/dev), `per_host` and `global` concurrency.
- Pre-emption: connection cancel + `KILL QUERY … ASYNC` backstop restricted to `repl_reader` and the chunk's `query_id`.
- Blackout windows, global pause, circuit breaker; runtime DNS re-check against the active-DC deny-list.
- Wait-reason rendering (host, signal, value, threshold — never query text).
- ~~Guard leader election~~ (replaced in M4 by a guard per process with Postgres read tokens; SPEC §3.1).
- Wire the guard into the M2 pipeline.

Exit criteria (Testcontainers, with the load generator)
- **Replication lag gating**: heavy inserts / a large mutation on "prod" create replication backlog on "DR" → a submitted copy stays `WAITING_FOR_SOURCE` with the right reason, and starts only after DR catches up and `stable_window` passes.
- **Lag during a chunk**: backlog created mid-chunk → the replication read ends within `poll_interval + 5 s`; chunk `PREEMPTED`; the job later succeeds with correct data.
- **Failover**: a foreign user query or an `INSERT` on "DR" → DR_ACTIVE within one poll; running chunks killed; nothing restarts until an operator clears the flag.
- Operator failover flag, blackout window, global pause and circuit breaker each block admission.
- Guard cannot reach a DR host → no reads start (fail closed); running chunk pre-empted.
- The guard never kills another user's query (negative test with a foreign query whose `query_id` has the same prefix).
- A read without a token is rejected in code; a read without a `_partition_id` predicate is refused (unit + IT via `force_index_by_date`).
- Workload DDL applies cleanly on 25.8 and 26.8; with merges and our reads contending on "DR", background work gets the configured share (measured).
- **Zero contact with "prod"**: its `query_log` and connection metrics show no `repl_*` activity across the whole M3 suite.
- Security review of the guard and the DR provisioning files.

### M4 — Control plane: jobs, workers, REST API (M/L) — ✅ done

Delivered:
- `modules/control`: the Postgres schema (Flyway) and the job service: catalog, estimates, submission with RBAC, quotas, idempotency, coalescing and the recent-copy confirmation. Plus the worker, which handles planning, `SKIP LOCKED` chunk claims with weighted queues and per-user caps, leases with heartbeats, pre-emption accounting, partition assembly under advisory locks, finalisation, cancellation and the reaper. Also the registry (Postgres plus `repl_meta.copy_history`), the audit log in the same transaction, Postgres-backed pause/failover flags, and leased read tokens.
- `modules/app`: Spring Boot 4.1 app with process roles `api`/`worker`/`all`, the REST API from SPEC §7.5 (plus `GET /me`), an OAuth2/OIDC resource server (JWT user and groups claims are configurable), OpenAPI at `/v3/api-docs`, and health probes.
- The CLI gained `submit` (with `--wait`), `dry-run`, `status`, `jobs` and `cancel` against the API.
- Tests:
  - 8 control-plane ITs;
  - 6 API ITs (Spring app, real JWT validation, CLI end to end);
  - a guarded control-plane IT on the prod/DR topology (in GuardIT);
  - CLI unit tests against a stub server.

Found while building and testing (all fixed and covered):
1. Planning read DR metadata whatever the guard state. Planning now waits unless the guard is GREEN or AMBER, and the job shows the guard's reason while PENDING.
2. The global pause did not stop unguarded (`dc: test`) sources. Workers now check the pause/failover flags before planning or claiming a chunk.
3. The active config version was per process, so a reload through one API replica was invisible to the others. It now lives in Postgres.
4. The generated OpenAPI schema used camelCase while the API speaks snake_case.

Deviations:
- No single guard leader: every process that touches DR runs its own guard; global limits are Postgres read tokens (SPEC §3.1).
- Plain JDBC instead of jOOQ.
- Spring Boot 4.1 instead of 3.x.

Not done in M4 (moved):
- The `watermarks`/`fingerprints` tables (M5).
- `guard_state_history` and Prometheus metrics (M7).
- Completion notifications/webhooks (Q7).
- The worker-crash IT simulates the crash: a chunk is left RUNNING under a dead worker whose lease has expired. Killing a real worker process, and behaviour during a Postgres outage, move to the M7 chaos tests.


Scope
- Postgres schema (Flyway): `jobs`, `chunks`, `partition_tasks`, `leases`, `tokens`, `watermarks`, `fingerprints`, `guard_state_history`, `copy_registry`, `audit_log`, `config_versions`, `ops_flags` (pause, failover).
- Queue: `FOR UPDATE SKIP LOCKED` claim, leases + heartbeats, retry/backoff, pre-emption accounting, advisory locks per `(target, table, partition)`.
- Scheduling: two queues with weighted fair share, per-user caps, request coalescing, serialisation of overlapping requests on shared targets (SPEC §7.3).
- Job lifecycle and cancellation (SPEC §7.2), including killing running reads and dropping staging.
- Spring Boot app with roles; REST API from SPEC §7.5; OpenAPI generation; OIDC resource server; RBAC + quotas (SPEC §7.4); idempotency keys; failover-flag endpoints.
- "Last copied" registry written on each REPLACE (Postgres + `repl_meta.copy_history`).
- Audit log in the same transaction as state transitions.
- CLI `submit/status/cancel/dry-run` against the API.

Exit criteria
- IT: submit via API → job runs through the guard → SUCCEEDED; audit and registry rows complete.
- IT: kill a worker process mid-run → lease expiry → another worker completes.
- IT: two identical submissions → one job, two requesters notified.
- IT: overlapping requests on the same slice → serialised, final state matches the later request.
- IT: RBAC — user without access to a dataset/target gets 403; over-quota request rejected with a message.
- IT: cancel during RUNNING → read killed, staging dropped, target unchanged.

### M5 — Continuous mode (M)

Scope
- Scheduler role creating poll jobs from `continuous:` entries (leader-elected).
- `append_watermark` per shard (overlap, dedupe modes, `only_recent_partitions`), watermark persistence after commit.
- `partition_fingerprint` per shard (metadata fingerprint, optional checksum verify, re-check after copy, `sync_drops`).
- `scheduled_full_refresh` (cron, TZ).
- End-to-end lag metrics (with the DR replication-delay component) and alerts; pause/resume API.

Exit criteria
- IT: rows inserted on "prod" replicate to "DR" and appear on the target within `interval + copy time` while the guard is GREEN — without the tool touching "prod".
- IT: late rows within `overlap` are picked up without duplicates.
- IT: a mutation on "prod" → replicated to "DR" → affected partition re-copied (fingerprint mode).
- IT: a partition dropped on "prod" → dropped on the target when `sync_drops`.
- IT: guard RED for 10 minutes → lag grows, then catches up after GREEN; no reads during RED.

### M6 — Portal (M)

Scope
- React + TS SPA served by the app; OIDC login (authorization code + PKCE).
- Pages: New request (dataset or table → COB date(s) → shared target → estimate incl. **last-copied info and recent-overwrite confirmation** → submit), My jobs (state, queue position, wait reason, per-shard progress, data freshness, ETA, cancel), Job detail, Target history (last-copied registry), Guard status (banner + page incl. DR_ACTIVE), Continuous status, Ops/Admin (pause/resume, failover flag, config version).
- Polling or SSE for live updates; completion notifications in the portal (optional webhook).

Exit criteria
- Playwright E2E against the dockerised stack: a QA user selects `trading-eod` for a COB date and target `qa1`, sees the estimate and last-copied info, submits, sees `WAITING_FOR_SOURCE` while the load generator creates DR replication backlog, then progress, then SUCCEEDED; masked data verified on the target.
- E2E: a second user replacing the same slice within 24 h must tick the confirmation.
- Accessibility check (axe) passes on the main flows.

### M7 — Hardening and production readiness (M/L)

Scope
- `mv_mode` variants (SPEC §5.2).
- Grafana dashboard and Prometheus alert rules (SPEC §11), including `chrepl_active_dc_violations_total` and DR_ACTIVE.
- Target retention janitor.
- `rekey` job type for masking key rotation.
- Helm chart, resource limits, PodDisruptionBudgets, **egress NetworkPolicy** (DR hosts, targets, Postgres, OIDC, key store only).
- **Load test** on a prod-like staging pair (active + DR replicas, 3 shards): replay a representative ingest workload into the "active" side, run replication at full allowed concurrency on the DR side, and measure DR `absolute_delay`, replication queue and fetch throughput with and without the tool; tune thresholds.
- `docs/runbook.md`: pause/drain, **DC failover and failback** (set/clear failover flag, swap source/deny-list config), adding a table, handling drift, rekey, stuck job, guard UNKNOWN.
- Security review / pen test of the portal and API.

Exit criteria
- Load test meets the impact budget (SPEC §1.3): zero connections to the active side; DR `absolute_delay` p99 increase ≤ B seconds (proposed 5 s); pre-emption latency ≤ `poll_interval + 5 s` at p99. Budget and thresholds signed off by the repository owner.
- Failover drill in staging: promoting the DR side stops all replication reads within one poll interval.
- Runbook reviewed by on-call; dashboards and alerts live in staging.

### M8 — Fortanix DSM key provider (S/M)

Scope
- `fortanix` `MasterKeyProvider`: authenticate (API key or client certificate), unwrap the HMAC master key with a non-exportable KEK (or export an exportable HMAC security object), cache in memory, version mapping.
- Migration path from `static`: import the same key material into DSM (BYOK) with the same version → identical masked values, no rekey.
- Failure handling: DSM unreachable at start-up → workers refuse chunks (fail closed); no per-value DSM calls.

Exit criteria
- IT against a DSM sandbox or a mock implementing the DSM unwrap API: masked output identical to the `static` provider for the same key material.
- Security review of DSM credentials handling.

### Later (not scheduled)

- Restricted custom filters (SPEC §7.6).
- Kafka-tap source for continuous mode (consume ingestion topics, zero ClickHouse reads).

---

## 4. Dependency graph

```
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─┬─► M5 ─┬─► M7
                            └─► M6 ─┘
       M1 ───────────────────────────────► M8 (any time after M1; before key custody moves to DSM)
```

M1 and the read/write half of M2 can proceed in parallel with two engineers; M5 and M6 can
proceed in parallel after M4.

## 5. Risks and mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Each DR shard has only one replica, shared with fetches/merges | Our reads slow DR replication → RPO grows | Replication-lag gating and pre-emption; concurrency 1 per host; workload scheduling favouring background work; M7 load test against the impact budget. |
| DR access storage replicated via Keeper, or DDL run `ON CLUSTER` | Replication users appear on the active DC | Provision via `users.d` XML on DR nodes only; `validate-config --online` checks `system.user_directories`; runbook warning. |
| Failover not detected (DR serves traffic but no foreign queries seen in a poll) | Reads on a now-active DC | Failover flag is step 1 of the failover runbook; foreign queries and `InsertQuery` deltas as safety net; manual clear only. No external role signal exists. |
| DNS change points a DR name at the active DC | Contact with active DC | Runtime DNS re-check against the CIDR deny-list; NetworkPolicy blocks egress regardless. |
| Workload-scheduling syntax/behaviour differs between 25.8 and 26.8 | One fewer in-server safeguard | Version-specific DDL tested in the CI matrix; guard + L1 limits still apply. |
| `force_index_by_date` not satisfiable for some tables | No server-side scan guard | Validation flags them; only small `full_copy` tables may relax it. |
| Masking collisions on unique columns | Key violations in QA data | `fpe_ff1` for keys; `collision_check`; property tests. |
| New PII column added to prod unnoticed | Leak to non-prod | Fail-closed `UNCLASSIFIED_COLUMN`; PII name scanner; drift alerts. |
| Shared targets: users overwrite each other's slices | Confusing QA results | Last-copied registry, recent-overwrite confirmation, serialisation, notifications. |
| Guard too strict → replication rarely runs | Stale QA data | Time-in-state dashboards per reason; tune in M7; explicit blackout windows. |
| Static key leaked from the secret store | Masked values linkable/reversible (`fpe_ff1`) | Secret mounted read-only to workers only; audit; move to Fortanix (M8); rotate + `rekey` if exposed. |

## 6. Decisions still needed

From SPEC §13.2:

- **Before M3**: monitoring users to ignore (Q2).
- **Before M4/M6**: target topologies (Q8), notifications (Q7), retention on shared targets (Q6).
- **Before M7**: impact-budget values and threshold sign-off (Q1), network path DR → targets (Q3), blackout windows (Q5).
- **Before M8**: Fortanix wrap/unwrap vs exportable key, worker authentication (Q4).
