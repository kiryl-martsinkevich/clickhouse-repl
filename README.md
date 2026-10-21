# clickhouse-repl

A tool for copying data from a production ClickHouse cluster into non-production
environments (QA, UAT, perf), with consistent masking of sensitive columns.

- **Configured replication**: a list of databases and tables, with masking policies per column.
- **Consistent masking**: deterministic, keyed masking, so the same value masks the same way across tables and runs and joins still work.
- **Continuous mode**: keeps a configured set of tables near-real-time in sync.
- **Ad-hoc mode**: QA users request a table or dataset for a COB date from a web portal, and the job runs in the background.
- **Production first**: reads come only from the passive DR datacentre — the active DC is never contacted. A Source Guard admits reads only while DR replication is healthy, pre-empts them when it falls behind, stops immediately if DR becomes active, and fails closed.

Status: design phase. See:

- [Design specification](docs/SPEC.md)
- [Implementation plan](docs/PLAN.md)
- [Example configuration](docs/examples/replicator.yaml)
