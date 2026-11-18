# clickhouse-repl

A tool for copying data from a production ClickHouse cluster into non-production
environments (QA, UAT, perf), with consistent masking of sensitive columns.

- **Configured replication**: a list of databases and tables, with masking policies per column.
- **Consistent masking**: deterministic, keyed masking, so the same value masks the same way across tables and runs and joins still work.
- **Continuous mode**: keeps a configured set of tables near-real-time in sync.
- **Ad-hoc mode**: QA users request a table or dataset for a COB date from a web portal, and the job runs in the background.
- **Production first**: reads come only from the passive DR datacentre — the active DC is never contacted. A Source Guard admits reads only while DR replication is healthy, pre-empts them when it falls behind, stops immediately if DR becomes active, and fails closed.
- **Built for physical clusters**: prod and DR as replicas of one cluster with Keeper on the same machines, replicated non-production targets, and copies triggered from the portal, the CLI or any system that can call the REST API.

Status: every milestone of the [plan](docs/PLAN.md) is implemented (M0–M9). Items that need people or a
staging environment are listed there as open: the staging load test and sign-off, the failover drill,
the pen test, and the run against a real Fortanix DSM tenant.

Docs:

- [Design specification](docs/SPEC.md)
- [Runbook](docs/runbook.md) · [Load test](docs/loadtest.md) · [Security review](docs/security-review.md) · [Dependency and image scanning](SECURITY.md)
- [Implementation plan](docs/PLAN.md)
- [Example configuration](docs/examples/replicator.yaml)

## Demo

One command starts the physical layout in miniature, in Docker:
- 3 "prod" + 3 "DR" ClickHouse replicas of one shard (ReplicatedMergeTree), with Keeper on every
  node as one ensemble in which the DR Keepers cannot lead;
- the `qa1` target as 1 shard × 3 replicas with its own Keepers;
- Postgres, a development-only OIDC provider and the app.

It then seeds fictional trading data on prod and copies one COB date of the `trading-eod` dataset
to `qa1` with a `POST /api/v1/jobs` call, as QA user `alice` would. Needs Docker with Compose
(about 4 GB of memory for the nine ClickHouse nodes), JDK 21 and Node.js 22.

```bash
demo/run.sh          # build, start, seed, copy, and show source vs masked rows
demo/run.sh down     # remove everything
```

What it shows (abridged output):

```text
▶ Sample tables on all 6 replicas (ReplicatedMergeTree); data inserted on prod1 only and replicated to DR
rows on dr1: 6000 trades
rows on dr2: 6000 trades
rows on dr3: 6000 trades

▶ Waiting for the Source Guard to see the DR replicas and DR Keepers as GREEN
{"level":"GREEN","hosts":{"dr1:8123":"GREEN","dr2:8123":"GREEN","dr3:8123":"GREEN"},"reasons":[],"active_reads":0,
 "keepers":{"dr1:9181":{"level":"GREEN","state":"standalone","avg_latency_ms":0.0,"outstanding_requests":0},
            "dr2:9181":{"level":"GREEN","state":"standalone",...},"dr3:9181":{"level":"GREEN","state":"standalone",...}},...}

▶ alice (QA) estimates a copy of the trading-eod dataset for COB 2026-10-01 to qa1
trading.trades                        1 partitions           6000 rows           215821 bytes
trading.positions                     1 partitions           3000 rows            49107 bytes
crm.customers                         1 partitions           2000 rows            49061 bytes
refdata.instruments                   1 partitions            500 rows             6674 bytes

▶ ... and submits it through the REST API (the CLI and the portal use the same call)
{"id":"jd2728ddba7a34243","kind":"adhoc","requester":"alice","target":"qa1","dataset":"trading-eod","state":"PENDING",...}
jd2728ddba7a34243 SUCCEEDED qa1 chunks 4/4 partitions 4/4 rows 5500

▶ Production data (queried on prod by the demo, never by the tool)
   ┌─trade_id─┬─customer_id─┬─account_number───────┬─counterparty_name─┬─notional─┬─trader_comment─────┬─client_email─────┐
1. │        0 │      100000 │ GB29NWBK601600000000 │ Northwind Capital │        0 │ call client 100000 │ client0@bank.com │
2. │        1 │      100001 │ GB29NWBK601600000001 │ Cobalt Capital    │     0.01 │ ᴺᵁᴸᴸ               │ client1@bank.com │

▶ The same rows on qa1: masked consistently, pass columns unchanged
   ┌─trade_id─┬──customer_id─┬─account_number───────┬─counterparty_name────┬─notional─┬─trader_comment─┬─client_email────────┐
1. │        0 │ 901213632431 │ LC49OBHQ249198435049 │ Acme Holdings        │        0 │ ᴺᵁᴸᴸ           │ esjfrd0@example.com │
2. │        1 │ 978569096350 │ EE21LEHD766762948712 │ Foxglove Investments │     0.01 │ ᴺᵁᴸᴸ           │ zgecue2@example.com │

▶ Consistency: masked customer ids still join across tables
   ┌─trades─┬─with_customer─┐
1. │   2000 │          2000 │

▶ Every qa1 replica has the copy (written once, replicated by qa1's own Keepers)
qa1-1: 2000 trades, engine ReplicatedMergeTree
qa1-2: 2000 trades, engine ReplicatedMergeTree
qa1-3: 2000 trades, engine ReplicatedMergeTree

▶ Which DR replicas served the chunk reads (any admissible replica; at most one read per replica at a time)
dr1: 3 chunk reads
dr2: 1 chunk reads
dr3: 0 chunk reads

▶ The tool never touched prod
queries on prod1-3 by repl_* users: 0
```

Then open the portal at http://localhost:8080 and sign in as `alice`, `bob` (QA), `olga`
(operator) or `root` (admin) on the dev sign-in page.

## Cluster layout

The typical deployment, and what the [example configuration](docs/examples/replicator.yaml), the
demo and `ClusterIT` use:

```text
          active DC (prod)                    passive DC (DR)                 non-production
  ┌───────────┬───────────┬───────────┐ ┌──────────┬──────────┬──────────┐ ┌──────────┬──────────┬──────────┐
  │ ch-prod-1 │ ch-prod-2 │ ch-prod-3 │ │ ch-dr-1  │ ch-dr-2  │ ch-dr-3  │ │ ch-qa1-1 │ ch-qa1-2 │ ch-qa1-3 │
  │ + Keeper  │ + Keeper  │ + Keeper  │ │ + Keeper │ + Keeper │ + Keeper │ │ + Keeper │ + Keeper │ + Keeper │
  └───────────┴───────────┴───────────┘ └──────────┴──────────┴──────────┘ └──────────┴──────────┴──────────┘
   one shard, 6 replicas (ReplicatedMergeTree); one Keeper ensemble:        one shard, 3 replicas,
   DR Keepers have can_become_leader=false (non-voting learners)            its own Keeper ensemble
                 never contacted ✗          read + watched ✓                         written ✓
```

- **Source**: one shard listing its three DR replicas. Every replica is polled by the Source Guard,
  and each chunk is read from a healthy one. With `fail_closed_scope: replica`, a replica that lags
  is skipped while the others serve reads.
- **Keeper**: the guard polls each DR Keeper with the read-only `mntr` command. A slow or
  unreachable DR Keeper stops every read, and a DR Keeper leading the ensemble means failover
  (DR_ACTIVE). Prod writes do not depend on DR Keepers, which are learners.
- **Target**: `replicas: 3` with a `zookeeper_path_template`. Tables are created on every replica;
  data is written once, through the first healthy replica, and replicated by the target's own Keepers.
- Sharded layouts (`shards` with one `host` each, `same_shards` targets) remain supported.

Details: [SPEC §4.9 and §5.6](docs/SPEC.md#49-physical-clusters-dr-replicas-and-a-shared-keeper-m9).

## Build

Requires JDK 21. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew check                 # compile (warnings as errors), ktlint, tests
./gradlew :cli:installDist      # builds modules/cli/build/install/clickhouse-repl
./gradlew :app:bootJar          # builds modules/app/build/libs/clickhouse-repl-app.jar
```

## Run the control plane

The app needs Postgres, the replicator config and an OIDC issuer (or a public key) to validate
bearer tokens. On startup it migrates Postgres and validates the config online (DNS, secrets,
files). An invalid config stops it.

```bash
CHREPL_CONFIG=/etc/chrepl/replicator.yaml \
CHREPL_ROLES=all \
SPRING_DATASOURCE_URL=jdbc:postgresql://pg:5432/chrepl SPRING_DATASOURCE_USERNAME=chrepl SPRING_DATASOURCE_PASSWORD=... \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://sso.example.com/realms/corp \
java -jar modules/app/build/libs/clickhouse-repl-app.jar
```

| Setting | Meaning |
|---------|---------|
| `chrepl.roles` | `api` (REST API), `worker` (executes jobs), `scheduler` (continuous mode; one leader at a time), or `all`. Run several workers against one Postgres. |
| `chrepl.auth.user-claim` / `groups-claim` | JWT claims for the user name (default `preferred_username`) and OIDC groups (default `groups`); groups map to roles through the config's `access` rules. |
| `chrepl.worker.threads`, `lease` | Chunk concurrency per worker process and the chunk lease (default 4, 60 s). Cluster-wide read limits come from `guard.concurrency`. |

OpenAPI: `/v3/api-docs` (Swagger UI at `/swagger-ui.html`); health: `/actuator/health`.

## Portal

QA users request copies in the browser: dataset or tables → COB dates → shared target → estimate
(with who last replaced the slice) → submit. They then follow the job while it waits for the DR
cluster and runs.

| Waiting for the DR cluster | Replacing someone else's recent copy |
|---|---|
| ![Job waiting for the source](docs/images/portal-waiting.png) | ![Estimate with confirmation](docs/images/portal-confirmation.png) |

Settings: `chrepl.portal.issuer` (the OIDC issuer the browser signs in with), `chrepl.portal.client-id`
(a public client; default `chrepl-portal`), `chrepl.portal.scope`. The browser E2E tests run with
`./gradlew :it:test -Pit -Pe2e --tests '*PortalE2EIT*'` (needs Node.js 22 and Chromium).

## Deploy

- `Dockerfile`: minimal Chainguard JRE runtime image (non-root, no known CVEs; see [SECURITY.md](SECURITY.md)). Build the jar first with `./gradlew :app:bootJar`.
- `deploy/helm/clickhouse-repl`:
  - one Deployment per role (`api`, `worker`, `scheduler`), with PodDisruptionBudgets;
  - a deny-by-default egress NetworkPolicy: DR replicas (ClickHouse and Keeper ports), targets, Postgres, OIDC and the key store only;
  - actuator on a management port that the Ingress does not expose.
- `deploy/monitoring`: Grafana dashboard and Prometheus alert rules (`promtool test rules alerts.test.yaml`).
- `deploy/dr-ddl.sql` and `deploy/dr-users.d/`: DR users, profiles, quotas and workload scheduling. `deploy/target-ddl.sql`: target marker and writer.

## Request copies from the command line

```bash
export CHREPL_API=https://chrepl.internal CHREPL_TOKEN=$(get-my-oidc-token)
clickhouse-repl dry-run --dataset trading-eod --cob 2026-10-19 --target qa1      # estimate only
clickhouse-repl submit  --dataset trading-eod --cob 2026-10-19 --target qa1 --wait
clickhouse-repl submit  --table trading.trades --cob-from 2026-10-01 --cob-to 2026-10-09 --target qa1
clickhouse-repl status <job-id> --chunks
clickhouse-repl jobs --mine
clickhouse-repl cancel <job-id>
```

`--json` prints raw API responses. Exit codes: `0` ok, `1` the job did not succeed (`--wait`),
`3` the request was rejected (the server's reason is printed), `4` the API is unreachable.

## Trigger copies through the API

Any system that can send an HTTP request (a CI pipeline, a test harness, a scheduler) triggers a
copy with the same call the portal and CLI make. It needs a bearer token for a user or service
account whose OIDC groups grant `requester` on the dataset or tables and target (config `access`).

```bash
API=https://chrepl.internal
TOKEN=$(get-my-oidc-token)   # e.g. a client-credentials token for a service account

# Estimate first (optional): sizes, queue position, who last replaced the slice.
curl -fsS -X POST "$API/api/v1/estimates" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"dataset": "trading-eod", "cob": {"dates": ["2026-10-19"]}, "target": "qa1"}'

# Submit. The Idempotency-Key makes a retried call return the same job instead of starting another.
curl -fsS -X POST "$API/api/v1/jobs" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: nightly-qa1-2026-10-19" \
  -d '{"dataset": "trading-eod", "cob": {"dates": ["2026-10-19"]}, "target": "qa1", "mode": "replace"}'
# → {"id":"jb2cbf33aa31b43e9","state":"PENDING",...}

# Tables instead of a dataset, and a COB range; skip partitions already on the target.
curl -fsS -X POST "$API/api/v1/jobs" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tables": ["trading.trades"], "cob": {"from": "2026-10-01", "to": "2026-10-09"}, "target": "qa1", "mode": "skip_if_present"}'

# Follow it until state is SUCCEEDED, FAILED, CANCELLED or BLOCKED (wait_reason explains any waiting).
curl -fsS "$API/api/v1/jobs/jb2cbf33aa31b43e9" -H "Authorization: Bearer $TOKEN"
```

Errors are `{"error": code, "message": …}`. A `409 confirm_overwrite_recent` means someone else
replaced the same slice recently: resubmit with `"confirm_overwrite_recent": true` if that is
intended. `422 quota` means the request exceeds the group's quota. The full API is described by
the OpenAPI document at `/v3/api-docs` and in [SPEC §7.5](docs/SPEC.md#75-rest-api-v1).

## Validate a configuration

```bash
modules/cli/build/install/clickhouse-repl/bin/clickhouse-repl validate-config docs/examples/replicator.yaml
```

The path can be a file or a directory of `*.yaml` files, which are merged in lexical order.
Checks are offline by default, which suits CI. Optional flags:

| Flag | Adds |
|------|------|
| `--resolve-dns` | Resolves every source/target host and rejects addresses inside the active-DC CIDRs. |
| `--resolve-secrets` | Reads `${file:…}` / `${env:…}` secrets and checks the masking key is at least 256 bits. |
| `--check-files` | Checks that referenced dictionary files exist. |
| `--format json` | Machine-readable output. |

Exit codes: `0` valid (warnings allowed), `1` invalid, `2` the configuration cannot be read.
Every issue carries a stable code (e.g. `ACTIVE_DC_HOST`, `UNCLASSIFIED_COLUMNS`) and a path
into the config (e.g. `sources.dr.shards[0].replicas[1]`).

## Layout

| Path | Contents |
|------|----------|
| `modules/config` | Configuration model, YAML loader, semantic validation (SPEC §9). |
| `modules/masking` | Masking engine: keys, strategies, type-aware table maskers (SPEC §6). `./gradlew :masking:jmh` runs the benchmarks. |
| `modules/types` | ClickHouse type model and parser. |
| `modules/clickhouse` | HTTP client and RowBinary codec. |
| `modules/transfer` | Schema sync, chunk planning, copy pipeline (SPEC §5). |
| `modules/guard` | Source Guard: DR signals, admission, pre-emption, DR_ACTIVE (SPEC §4). |
| `modules/control` | Control plane core: Postgres schema, job service, RBAC/quotas, workers, registry, audit (SPEC §7). |
| `modules/app` | Spring Boot application: REST API, OIDC resource server, process roles; serves the portal. |
| `modules/portal` | Portal SPA (React + TypeScript, Vite) and its Playwright E2E specs. |
| `modules/devidp` | Development-only OIDC provider for E2E tests and the demo. Never deploy it. |
| `modules/cli` | `clickhouse-repl` command-line tool (`validate-config`, `copy`, `dsm-wrap`, and API commands `submit`, `dry-run`, `status`, `jobs`, `cancel`, `rekey`). |
| `modules/it` | Docker integration tests: `./gradlew :it:test -Pit` (optionally `-Pchrepl.it.image=clickhouse/clickhouse-server:26.8`). |
| `build-logic` | Shared Gradle conventions (Kotlin, JDK 21 toolchain, ktlint, JUnit, dependency locking). |
| `deploy/` | DR and target provisioning SQL, Helm chart, monitoring (dashboard, alerts), sample dataset. |
| `demo/` | The Docker Compose demo (`demo/run.sh`). |
