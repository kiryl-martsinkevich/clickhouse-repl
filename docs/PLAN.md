# clickhouse-repl — Implementation Plan

Status: **Draft for review** · Companion to [SPEC.md](SPEC.md)

This plan delivers the design in [SPEC.md](SPEC.md) in milestones that are each
shippable and testable on their own. The order puts the **masking engine** and the
**Prod Guard** — the two parts that protect data and production — ahead of
convenience features, and nothing reads real production data until the guard
milestone (M3) has passed its tests.

---

## 1. Repository layout

Gradle (Kotlin DSL) multi-module build, JDK 21, Kotlin 2.x.

```
clickhouse-repl/
├── build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml
├── modules/
│   ├── config/          # YAML model, JSON Schema, semantic validation, secret resolution
│   ├── masking/         # strategies, key derivation, type codecs, policy completeness checks
│   ├── clickhouse/      # client-v2 wrapper: streaming read/insert, settings, query_id, schema introspection
│   ├── guard/           # signal collectors, state machine, tokens, pre-emption, blackout, circuit breaker
│   ├── transfer/        # schema sync, chunk planner, read→mask→staging→REPLACE pipeline, janitor
│   ├── jobs/            # Postgres job/chunk store, SKIP LOCKED queue, leases, scheduler, audit
│   ├── app/             # Spring Boot app: REST API, OIDC/RBAC, roles (api|scheduler|guard|worker|all)
│   ├── cli/             # validate-config, dry-run, submit/status/cancel (REST client)
│   └── it/              # integration tests (Testcontainers: ClickHouse "prod" + "target", Postgres)
├── portal/              # React + TypeScript + Vite SPA (built into app's static resources)
├── deploy/
│   ├── prod-ddl.sql     # users, profiles, quotas, workloads for prod (applied by DBAs)
│   ├── target-ddl.sql   # repl_writer, repl_meta.* on targets
│   └── helm/            # chart: api/scheduler/guard (2 replicas), workers (N)
└── docs/                # SPEC.md, PLAN.md, examples/, runbook.md (M7)
```

Module dependencies flow one way: `config` ← `masking` ← `transfer` → `clickhouse`,
`guard` → `clickhouse`, `jobs` → `config`, `app` → everything. `masking` and `guard`
have no Spring dependency, so they are unit-testable in isolation.

## 2. Cross-cutting rules (apply to every milestone)

- **No test, demo, or CI job ever connects to a real production cluster.** Integration tests use two ClickHouse Testcontainers labelled `prod` and `target`.
- Every prod query is built in one place (`clickhouse` module `ProdQueryBuilder`) that always attaches the `query_id`, the L1-compatible settings, and the `_partition_id` predicate. A unit test fails if any other code path issues `SELECT` against a source.
- Fail-closed defaults: unknown → deny, in config validation, masking policy lookup and the guard.
- Every PR: `./gradlew check` (ktlint, detekt, unit tests), integration tests on main, portal `npm run lint && npm test`.
- Security review sign-off required at the end of M1 (masking) and M3 (guard).

---

## 3. Milestones

Sizes are relative (S ≈ 1 week, M ≈ 2 weeks, L ≈ 3+ weeks for one engineer) and are for
sequencing, not commitments.

### M0 — Project skeleton and configuration (S)

Scope
- Gradle multi-module build, version catalog, ktlint/detekt, GitHub Actions CI.
- `config` module: Kotlin data model for all sections in SPEC §9.2, JSON Schema, YAML loading, `${vault:…}`/`${env:…}` secret references (Vault client behind an interface; env-only implementation for dev).
- Semantic validation from SPEC §9.3 (except online schema checks): policy completeness, partition-key-not-masked (offline: declared), target ≠ prod host deny-list, domain references, guard/continuous sanity.
- `cli validate-config <path>` with human-readable errors.
- `docs/examples/replicator.yaml` validates in CI.

Exit criteria
- Example config validates; a table of negative fixtures (missing column policy, masked partition key, target host = prod host, unknown domain, preemption disabled) each fails with the expected error.

### M1 — Masking engine (M)

Scope
- Key handling: master key provider interface (Vault Transit/KV, KMS, local file for tests), HKDF per (realm, version, domain), keys zeroed on shutdown, never logged (log redaction test).
- Canonicalisation per type; normalisers (trim, lower, NFC).
- Strategies: `hash`, `tokenize`, `fpe_ff1` (Bouncy Castle), `email`, `dictionary`, `date_shift`, `numeric_noise`, `partial`, `null`, `constant`.
- Type codecs for ClickHouse types incl. Nullable, LowCardinality, Array, Map (keys/values), Tuple, JSON paths in String/JSON columns.
- Row masker: compiled per table spec (column index → masker), entity-column lookups, per-domain LRU cache, collision check option.
- Micro-benchmarks (JMH) for throughput per strategy.

Exit criteria
- Property tests (Kotest): determinism (same input → same output across instances/runs), realm and version separation, format preservation for `tokenize`/`fpe_ff1`, type-range safety, NULL handling, `fpe_ff1` bijectivity on sampled domains.
- Cross-table consistency test: `customer_id` as `UInt64` in one table and `String` in another mask to the same value.
- Throughput ≥ 50 MB/s uncompressed per core on the benchmark tables.
- Security review of key handling and strategies.

### M2 — Batch transfer via CLI (M/L)

Scope
- `clickhouse` module: client-v2 wrapper with streaming `RowBinaryWithNamesAndTypes` read, streaming `RowBinary` insert, backpressure through a bounded pipe, per-query settings, deterministic `query_id`, cancellation.
- Schema sync (SPEC §5.2): `SHOW CREATE TABLE` rewrite (cluster, ZooKeeper paths, overrides, Distributed), create on target, drift detection → `SCHEMA_DRIFT` / `UNCLASSIFIED_COLUMN`.
- Chunk planner (SPEC §5.3): `system.parts` partitions, COB → partition mapping, `EXPLAIN ESTIMATE`, sort-key range splitting.
- Writer (SPEC §5.4): per-chunk staging, `ATTACH PARTITION FROM`, sub-partition merge, `REPLACE PARTITION`, staging cleanup, janitor; target environment marker check before every write; `repl_meta` tables (environment, table key versions, copy history).
- `cli copy --table … --cob … --target …` runs end to end **without** the guard — usable only against Testcontainers / dev clusters (hard-coded refusal unless the source is marked `role: dev` in config).

Exit criteria
- IT: copy a monthly-partitioned table for one COB date into a target that already has other dates in that month → only that date replaced, other dates intact, masked columns masked.
- IT: re-run the same copy → identical target state (idempotency).
- IT: kill the worker mid-chunk → re-run completes, no duplicates.
- IT: prod gains a new column → job blocks with `UNCLASSIFIED_COLUMN`.
- IT: Array/Map/Nullable/LowCardinality/JSON columns round-trip with masking.
- IT: target marker mismatch → write refused.

### M3 — Prod Guard (L)

Scope
- `deploy/prod-ddl.sql`: users, settings profile with constraints, quota, workload DDL variants by ClickHouse version (SPEC §4.2). IT applies it to the `prod` container and asserts the limits (e.g. `max_threads` cannot be raised, writes are refused).
- Signal collectors per host (SPEC §4.4), direct per-host polling with timeouts; staleness → UNKNOWN.
- State machine GREEN/AMBER/RED/UNKNOWN with stable window and release ratio; per-host and per-cluster.
- Admission tokens (Postgres-leased; in-memory implementation for CLI/dev), concurrency limits.
- Pre-emption: connection cancel + `KILL QUERY … ASYNC` backstop restricted to `repl_reader` and the chunk's `query_id`.
- Blackout windows (cron + duration + TZ), global pause flag, circuit breaker.
- Wait-reason rendering (user, host, elapsed, memory — never query text).
- Guard leader election (Postgres advisory lock) and failover.
- Wire the guard into the M2 pipeline; remove the `role: dev` bypass for non-dev sources.

Exit criteria (all against Testcontainers, with a load generator issuing heavy foreign queries on `prod`)
- A heavy foreign query running on `prod` → submitted copy stays in `WAITING_FOR_PROD` with the correct reason; it starts only after the query ends and `stable_window` passes.
- A heavy query started **during** a chunk → replication query ends within `poll_interval + 5 s`; chunk re-queued as `PREEMPTED`; job eventually succeeds with correct data.
- Guard cannot reach a watched host → no reads start (fail closed); running chunk is pre-empted.
- Blackout window, global pause, and circuit breaker each block admission.
- The guard never kills a query of another user (negative test with a foreign query matching the same `query_id` prefix).
- An attempt to read without a token is rejected in code (unit test) and a read without a `_partition_id` predicate is refused (unit + IT via `force_index_by_date`).
- Security review of the guard and the prod DDL.

### M4 — Control plane: jobs, workers, REST API (M/L)

Scope
- Postgres schema (Flyway): `jobs`, `chunks`, `partition_tasks`, `leases`, `tokens`, `watermarks`, `fingerprints`, `guard_state_history`, `audit_log`, `config_versions`.
- Queue: `FOR UPDATE SKIP LOCKED` claim, leases + heartbeats, retry/backoff, pre-emption accounting, advisory locks per `(target, table, partition)`.
- Scheduling: two queues with weighted fair share, per-user caps, request coalescing (SPEC §7.3).
- Job lifecycle and cancellation (SPEC §7.2), including killing running reads and dropping staging.
- Spring Boot app with roles; REST API from SPEC §7.5; OpenAPI generation; OIDC resource server; RBAC + quotas (SPEC §7.4); idempotency keys.
- Audit log writes in the same transaction as state transitions.
- CLI `submit/status/cancel/dry-run` against the API.

Exit criteria
- IT: submit via API → job runs through guard → SUCCEEDED; audit rows complete.
- IT: kill a worker pod/process mid-run → lease expiry → another worker completes.
- IT: two identical submissions → one job, two requesters.
- IT: RBAC — user without access to a dataset/target gets 403; over-quota request rejected with message.
- IT: cancel during RUNNING → read killed, staging dropped, target unchanged.

### M5 — Continuous mode (M)

Scope
- Scheduler role creating poll jobs from `continuous:` entries (leader-elected).
- `append_watermark` (overlap, dedupe modes, `only_recent_partitions`), watermark persistence after commit.
- `partition_fingerprint` (metadata fingerprint, optional checksum verify, re-check after copy, `sync_drops`).
- `scheduled_full_refresh` (cron, TZ).
- Lag metrics and alerts; pause/resume API.

Exit criteria
- IT: rows inserted on `prod` appear on `target` within `interval + copy time` while the guard is GREEN.
- IT: late rows within `overlap` are picked up without duplicates.
- IT: `ALTER TABLE … UPDATE/DELETE` mutation on `prod` → affected partition re-copied (fingerprint mode).
- IT: partition dropped on `prod` → dropped on target when `sync_drops`.
- IT: guard RED for 10 minutes → lag grows, then catches up after GREEN; no reads during RED.

### M6 — Portal (M)

Scope
- React + TS SPA served by the app; OIDC login (authorization code + PKCE).
- Pages: Catalog / New request (dataset or table → COB date(s) → target → estimate → submit), My jobs (state, queue position, wait reason, progress, ETA, cancel), Job detail (per-table/chunk), Guard status (read-only banner + page), Continuous status (lag vs SLO), Admin (pause/resume, config version).
- Polling or SSE for live updates.
- Notifications on completion (portal; optional webhook for email/Slack).

Exit criteria
- Playwright E2E against the dockerised stack: a QA user selects `trading-eod` for a COB date and target `qa1`, sees the estimate, submits, sees `WAITING_FOR_PROD` while a heavy query runs on `prod`, then progress, then SUCCEEDED; masked data verified on target.
- Accessibility check (axe) passes on the main flows.

### M7 — Hardening and production readiness (M/L)

Scope
- Sharded sources (per-shard planning, read-replica selection, shard mapping to target).
- `mv_mode` variants (SPEC §5.2).
- Grafana dashboard and Prometheus alert rules (SPEC §11).
- Target retention janitor.
- `rekey` job type for masking key rotation.
- Helm chart, resource limits, PodDisruptionBudgets, network policies (workers egress only to prod read replicas/targets/Vault/Postgres).
- **Load test** on a prod-like staging cluster: replay a representative query workload, run replication at full concurrency, and measure prod query latency and resource usage with and without replication; tune guard thresholds.
- `docs/runbook.md`: pause/drain, adding a table, handling drift, rekey, stuck job, guard UNKNOWN.
- Penetration test / security review of the portal and API.

Exit criteria
- Load test: prod p99 query latency and error rate stay within the agreed impact budget (SPEC §1.3) with replication running; pre-emption latency ≤ `poll_interval + 5 s` at the 99th percentile.
- Runbook reviewed by the on-call team; dashboards and alerts live in staging.
- Go-live checklist signed off by data-platform, security, and prod DBAs.

### Later (not scheduled)

- Approval workflow for large ad-hoc requests.
- Restricted custom filters (SPEC §7.6).
- Per-user target namespaces with automatic cleanup.
- Kafka-tap source for continuous mode (consume prod ingestion topics, zero prod reads).

---

## 4. Dependency graph

```
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─┬─► M5 ─┬─► M7
                            └─► M6 ─┘
```

M1 and the read/write half of M2 can proceed in parallel with two engineers; M5 and M6
can proceed in parallel after M4.

## 5. Risks and mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| No dedicated read replica available on prod | Residual impact (page/mark cache, IO) on serving replicas | L1 cache-bypass settings, stricter guard thresholds, lower concurrency; escalate as an infra ask (SPEC §13 Q1). |
| Prod ClickHouse version lacks workload scheduling | One fewer in-server safeguard | Guard + L1 limits still apply; DDL has version-specific variants. |
| `force_index_by_date` not satisfiable for some tables | Cannot use the server-side scan guard | Validation flags them; only small `full_copy` tables may relax it. |
| Masking collisions on unique columns | Constraint/key violations in QA data | `fpe_ff1` for keys; `collision_check`; property tests. |
| New PII column added to prod unnoticed | Leak to non-prod | Fail-closed `UNCLASSIFIED_COLUMN`; PII name scanner; drift alerts. |
| Guard thresholds too strict → replication never runs | Stale QA data | Guard dashboards show time-in-state per reason; tune in M7 load test; blackout windows explicit. |
| Guard thresholds too loose | Prod impact | Start strict; loosen only with load-test evidence; circuit breaker. |
| Large ad-hoc requests starve continuous jobs | Lag SLO breaches | Weighted fair share, quotas, chunk sizing. |

## 6. Decisions needed before M2 starts

From SPEC §13: ClickHouse versions and topology (Q2, Q3), read-replica availability (Q1),
key custody (Q4). Before M6: target namespace model (Q5), approvals (Q6), notifications (Q10).
Before M7: impact budget and threshold sign-off (Q7), blackout windows (Q8), retention (Q9).
