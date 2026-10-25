# clickhouse-repl

A tool for copying data from a production ClickHouse cluster into non-production
environments (QA, UAT, perf), with consistent masking of sensitive columns.

- **Configured replication**: a list of databases and tables, with masking policies per column.
- **Consistent masking**: deterministic, keyed masking, so the same value masks the same way across tables and runs and joins still work.
- **Continuous mode**: keeps a configured set of tables near-real-time in sync.
- **Ad-hoc mode**: QA users request a table or dataset for a COB date from a web portal, and the job runs in the background.
- **Production first**: reads come only from the passive DR datacentre — the active DC is never contacted. A Source Guard admits reads only while DR replication is healthy, pre-empts them when it falls behind, stops immediately if DR becomes active, and fails closed.

Status: M0 (configuration), M1 (masking engine), M2 (batch transfer), M3 (Source Guard) and M4 (control plane: jobs, workers, REST API, CLI client) and M5 (continuous mode) implemented; see the plan for what comes next.

Docs:

- [Design specification](docs/SPEC.md)
- [Implementation plan](docs/PLAN.md)
- [Example configuration](docs/examples/replicator.yaml)

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
into the config (e.g. `sources.dr.shards[0].host`).

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
| `modules/app` | Spring Boot application: REST API, OIDC resource server, process roles. |
| `modules/cli` | `clickhouse-repl` command-line tool (`validate-config`, `copy`, and API commands `submit`, `dry-run`, `status`, `jobs`, `cancel`). |
| `modules/it` | Docker integration tests: `./gradlew :it:test -Pit` (optionally `-Pchrepl.it.image=clickhouse/clickhouse-server:26.8`). |
| `build-logic` | Shared Gradle conventions (Kotlin, JDK 21 toolchain, ktlint, JUnit). |
