# Load test: impact on the DR cluster

PLAN M7 exit criterion: replication reads must stay within the impact budget (SPEC §1.3).

| Criterion | Requirement |
|---|---|
| Active DC | zero connections |
| DR `absolute_delay` | p99 increase ≤ B seconds (proposed B = 5 s) |
| Pre-emption latency | ≤ `poll_interval` + 5 s at p99 |

The budget and thresholds need sign-off by the repository owner.

## 1. Container harness (`LoadIT`)

`./gradlew :it:test -Pit -Pload --tests '*LoadIT*'` (options: `-Pchrepl.load.seconds=60`,
`-Pchrepl.load.budget=5`). The setup:
- A Keeper, a "prod" replica and a "DR" replica, with steady ingest into "prod" (500 rows every 200 ms).
- DR replica delay and replication queue are measured every 500 ms, first without the tool and then while the tool copies a 200k-row COB repeatedly through the guard.
- The report is written to `modules/it/build/loadtest-report.md`.

The test fails if:
- the p99 delay increase exceeds the budget; or
- the tool touched "prod".

Pre-emption latency is measured by `GuardIT` ("backlog during a chunk pre-empts the read within a
poll plus grace").

Latest run, 25.8, one machine:

| | DR delay p50 | p99 | max | queue p99 |
|---|---|---|---|---|
| baseline | 0 s | 0 s | 0 s | 1 |
| with replication reads | 0 s | 0 s | 0 s | 1 |

The run completed 4 copies (28 reads on DR) with 0 pre-emptions and no connections to "prod".

**These numbers do not prove the budget.** Everything shares one machine, the reader is throttled to
300 kB/s in tests, and `absolute_delay` has 1 s resolution. The harness checks the method, not
production behaviour.

## 2. Staging run (required before production)

**Environment.** A prod-like pair:
- an "active" side and a DR side, each 3 shards × 1 replica, `ReplicatedMergeTree`;
- the same ClickHouse version and hardware class as production;
- `deploy/dr-ddl.sql` applied on DR only.

**Workload:**
1. Replay a representative ingest into the active side. Use a recorded day at 1× and 2× speed, including merges and mutations.
2. Run the tool with the production configuration and `guard.concurrency` at its maximum. Run:
   - ad-hoc copies of the largest datasets;
   - continuous streams at their configured intervals.

**Measure (Prometheus, 15 s scrape):**
- DR `absolute_delay` per replica: `chrepl_dr_replica_delay_seconds`, plus ClickHouse's own `system.replicas`.
- DR replication queue (`chrepl_dr_replication_queue`), fetch throughput (`ReplicatedFetch` events), merges in progress.
- Guard pre-emptions and their latency: `chrepl_preemption_latency_seconds` (histogram: time from pre-emption to the end of the read) and `chrepl_preemptions_total`.
- Connections from the tool's pods to the active side: must be 0. Check the active side's `system.query_log` for `repl_%` users; the honeypot users make any attempt visible. Check NetworkPolicy deny logs too.

**Procedure:**
1. 2 h with ingest only, as the baseline.
2. 2 h with ingest and the tool.
3. 30 min with a forced DR backlog: `SYSTEM STOP FETCHES` on one DR replica for 5 min, three times. Check that pre-emption happens within `poll_interval + 5 s` and that nothing restarts until DR is GREEN again.
4. Failover drill: set the failover flag mid-copy. Then, separately, run a query as a non-tool user on a DR replica. Both must stop all reads within one poll.

**Accept** when, compared with the baseline:
- the p99 delay increase is within the budget;
- pre-emption latency p99 is ≤ `poll_interval + 5 s`;
- there were zero connections to the active side;
- the failover drill stopped everything within one poll.

Then tune the thresholds (`guard.thresholds`), record the final values in the config, and get the
owner's sign-off.
