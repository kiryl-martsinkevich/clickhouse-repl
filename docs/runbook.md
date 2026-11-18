# clickhouse-repl runbook

For on-call. The design is in [SPEC.md](SPEC.md). Dashboards: `deploy/monitoring/grafana-dashboard.json`.
Alerts: `deploy/monitoring/alerts.yaml`.

Every action below works through the portal ("Operations"), the REST API, or the CLI. Examples
use the API with an admin or operator token in `$T` and the base URL in `$API`:

```bash
api() { curl -sS -H "Authorization: Bearer $T" -H 'Content-Type: application/json' "$@"; }
```

All operator actions are written to the `audit_log` table, with the acting user.

---

## Principles

- The tool **never connects to the active DC**. Every read goes to the passive DR replicas, through the Source Guard.
- When in doubt, **stop reads first**: set the failover flag or pause. Both are safe at any time. Running reads end within one poll interval plus 5 s, and nothing is lost: jobs resume later.
- Targets are disposable. The source is never written to.

---

## Pause and drain (global kill switch)

Use for DR maintenance, incidents, or anything unusual on DR. Requires the admin role.

```bash
api -X POST $API/api/v1/admin/pause -d '{"note":"INC-1234 DR maintenance"}'
```

- Running reads are pre-empted at the next guard poll; chunks become PREEMPTED and are retried after resume.
- Nothing new is planned or started, even for `dc: test` sources.
- To drain instead of stopping: wait until `chrepl_queue_depth` reaches 0, or cancel jobs (`POST /api/v1/jobs/{id}/cancel`).

Resume:

```bash
api -X POST $API/api/v1/admin/resume
```

---

## DC failover (DR becomes active)

**Step 1, before anything else:** set the failover flag (operator role).

```bash
api -X POST $API/api/v1/admin/failover -d '{"note":"DC failover, CHG-5678"}'
```

What happens next:
- All reads stop within one poll: the guard goes to DR_ACTIVE and pre-empts every read.
- The flag is sticky. It survives restarts and stays set until an operator clears it.

The guard also detects DR becoming active by itself and sets the same flag. It does this on any query from a user other than the tool's own on a DR host, on inserts on DR (SPEC §4.6), or when a DR Keeper reports itself leader of the shared ensemble (SPEC §4.9). The `ChreplDrActive` alert fires either way.

With the shared prod+DR Keeper ensemble, promoting DR includes reconfiguring Keeper so the DR
Keepers can vote and lead (they are learners with `can_become_leader=false` while DR is passive).
Set the flag **before** that step: the guard stops reads when it sees a DR Keeper lead, but only
after the change has happened.

**While failed over:**
- The old DR is now production, so the tool must not read from it.
- Do not clear the flag. Leave the tool stopped.

**Failback, or swapping roles long-term:**

1. When the original roles are back, check that the DR hosts are passive again:
   - no application traffic;
   - `system.replicas` healthy on every DR replica.
2. If the roles are swapped permanently (the old production side is now the passive side), change the configuration first:
   - `sources.dr.shards[*].replicas` (or `.host`) → the new passive replicas;
   - `sources.dr.keeper.hosts` → the new passive side's Keepers, which must be learners again (`can_become_leader=false`);
   - `active_dc.hosts` / `cidrs` → the new active side;
   - provision the DR users (`deploy/dr-ddl.sql`) on the new passive side, without `ON CLUSTER`;
   - change the Helm `networkPolicy.egress.drReplicas` (ClickHouse and Keeper ports).

   Validate before deploying:
   ```bash
   clickhouse-repl validate-config replicator.yaml --resolve-dns --resolve-secrets --check-files
   ```
   Then roll out. Source and guard settings are read at start-up, so restart the pods.
3. Clear the flag:
   ```bash
   api -X POST $API/api/v1/admin/failover/clear
   ```
   The guard is UNKNOWN until its first poll, then waits `stable_window` before admitting reads.

---

## Active-DC violation (`ChreplActiveDcViolation`)

Every process re-resolves the configured hosts every minute. The alert means a source or target
host now resolves into the active DC's CIDRs or is a deny-listed name.

- The failover flag has been set automatically. **Do not clear it** until the cause is understood.
- Check the logs for `ACTIVE-DC VIOLATION`; it names the config path and the address.
- Usual causes:
  - a DNS change, such as a CNAME moved during a DC switch;
  - a mistaken config change.

  Fix DNS or the config, check with `validate-config --resolve-dns`, then clear the flag.
- The NetworkPolicy blocks egress to the active DC anyway. A violation means a second layer caught it.

---

## Guard UNKNOWN (`ChreplGuardUnknown`)

The guard cannot assess at least one DR host, so it fails closed and nothing reads. Check:
- **Reachability.** From a pod, `curl https://<dr-host>:8443/ping`.
- **Guard user and grants:**
  - `repl_guard` exists on that replica;
  - `GRANT SHOW TABLES ON *.*` is present, or the guard sees no replicated tables (`NO_REPLICAS_VISIBLE`);
  - `KILL QUERY` is granted.
- **Credentials.** The guard password secret.

The guard page (`/#/guard` or `GET /api/v1/guard`) shows each host's level and reason.

With `fail_closed_scope: replica` an UNKNOWN replica only stops reads from itself; the alert still
fires so that it is looked at. An unreachable **DR Keeper** (`KEEPER_UNREACHABLE`) stops every read
whatever the scope: check `echo mntr | nc <dr-host> 9181` from a pod, and that the Keeper's
`four_letter_word_white_list` includes `mntr` (it does by default).

---

## DR Keeper slow (`ChreplKeeperUnhealthy`)

A DR Keeper is above `guard.thresholds.keeper` (`KEEPER_LATENCY`, `KEEPER_OUTSTANDING`) or
unreachable, so every read waits. The DR replicas depend on these Keepers; production does not
(DR Keepers are learners). Look at the "DR Keeper" panels on the dashboard and at the Keeper logs
on the DR machines. Reads resume by themselves once the Keeper has been quiet for `stable_window`.
Do not raise the thresholds to push copies through.

---

## DR replica maintenance (one of several DR replicas)

With `fail_closed_scope: replica`, nothing needs to be done in the tool: while the replica is down
or catching up it is UNKNOWN or RED, serves no reads, and reads continue on the other DR replicas.
Reads that were running on it are pre-empted and retried elsewhere. With `cluster` or `shard`
scope, pause first (see above) or expect copies to wait.

If the replica's machine also runs a DR Keeper, every read stops while that Keeper is down
(fail closed). For planned work, either accept the wait, or remove the Keeper host from
`sources.dr.keeper.hosts` for the window (a reviewed config change and restart) and add it back
afterwards.

Taking a DR replica out for good: remove it from `sources.dr.shards[*].replicas` (and its Keeper
from `keeper.hosts`) and from the Helm egress policy, then restart.

---

## A non-production replica is down

Each target shard writes through its first replica that answers; when it is down the next one
takes over within 10 s (up to 30 s more if the remaining replicas are read-only while the target's
Keepers elect a new leader), and ReplicatedMergeTree brings the returning replica up to date. Nothing
to do in the tool. If a table was first created while the replica was down, the replica gets it
at the next copy to that target. If **every** replica of a target shard is down, jobs for that
target fail with "no writable replica of target shard N" and can be resubmitted.

---

## Circuit breaker open (`ChreplCircuitBreakerOpen`)

Too many pre-emptions in a short window, so reads pause for `circuit_breaker.open_for`. Usually DR
is close to its limits for a long time. Check DR replication delay and queue on the dashboard.
Consider:
- lowering `guard.concurrency`;
- adding blackout windows for known heavy periods.

---

## A job is BLOCKED

`reason` says why:
- **`UNCLASSIFIED_COLUMN` / schema drift.** A column appeared at the source without a masking policy. Add a policy (`pass` needs a reason) and reload the config:
  ```bash
  api -X POST $API/api/v1/admin/config/reload
  ```
  Then resubmit. Blocked jobs are not retried automatically.
- **A target partition or sort key differs.** Drop the target table; the next copy recreates it.
- **A masked partition key.** This needs a config change; see `PARTITION_KEY_MASKED`.

---

## A job is stuck

- **PENDING with a wait reason.** The guard does not allow planning (DR busy or not assessable). This is expected; it starts when DR is GREEN or AMBER.
- **RUNNING with chunks PREEMPTED or waiting.** Same cause. `GET /api/v1/jobs/{id}/chunks` shows each chunk's reason.
- **RUNNING with no progress and no wait reason.** Check the workers:
  - leases expire after `chrepl.worker.lease` (default 60 s) and another worker takes over;
  - `chrepl_chunk_failures_total` rising means target trouble (disk, permissions).
- **Cancel if needed.** Cancelling stops running reads and drops staging tables; the target is left unchanged.

---

## Masking key rotation (rekey)

1. Provide the new key material under a new `masking.key.version`. With Fortanix DSM, import or create the new key with the new version.
2. Reload the config. From then on, writes to tables masked with the old version are refused ("run a rekey job").
3. For each target, re-copy everything it holds:
   ```bash
   clickhouse-repl rekey --target qa1     # or POST /api/v1/admin/rekey {"target":"qa1"}
   ```
   - Each stale table is re-copied for the COB dates it holds, or as a whole for full copies.
   - While a rekey runs, a table can mix old and new masking.
4. Check `repl_meta.tables FINAL` on the target: every table should show the new `key_version`.

---

## Adding a table

1. Add a table spec with a policy for every column.
   - Declare `partition_key` and `sharding_key` as on DR.
   - Optionally add the table to a dataset and to access rules.
2. Run `clickhouse-repl validate-config --resolve-dns --resolve-secrets --check-files`.
3. Make sure `repl_reader` can `SELECT` the database on every DR replica (`deploy/dr-ddl.sql`).
4. Reload: `POST /api/v1/admin/config/reload`, or redeploy.

---

## Continuous lag (`ChreplContinuousLag`)

Lag (`chrepl_continuous_lag_seconds`) includes DR's own replication delay. Check in this order:
1. **The guard.** If it is RED for long stretches, lag growing is expected and it will catch up.
2. **The stream's `last_error`.** See `GET /api/v1/continuous` or the portal's Continuous page.
3. **Paused streams.** A stream an operator paused never catches up; resume it.

---

## Retention on shared targets

Targets with `retention` drop COB partitions whose newest COB date is older than the retention.
The scheduler leader runs this hourly. Every drop is in `audit_log` (`retention.drop`) and counted
in `chrepl_retention_dropped_partitions_total`. To keep data longer, raise `retention` and reload.

---

## Recovering from a lost Postgres

Postgres holds jobs, registry, audit and continuous state. While it is down, nothing starts. Running
reads end because their leases and tokens cannot be renewed.

After a restore, continuous streams re-poll. Partitions whose fingerprints were lost are re-copied
once.
