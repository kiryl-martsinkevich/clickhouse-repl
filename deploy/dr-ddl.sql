-- Run by the DBAs on EACH passive DR node individually (SPEC §4.2).
--   * Never ON CLUSTER: the cluster definition may include active-DC replicas.
--   * Only with LOCAL access storage (confirmed for this deployment). With replicated (Keeper)
--     access storage these users would reach prod; use deploy/dr-users.d/ instead.
-- Replace <reader-secret>, <guard-secret>, the worker CIDR and the database list.

-- Workload scheduling (ClickHouse 25.8+): background work (merges, mutations) gets ~90% of disk
-- and CPU when it contends with replication reads. Server config on DR nodes must also set:
--   <merge_workload>background</merge_workload> and <mutation_workload>background</mutation_workload>
CREATE RESOURCE IF NOT EXISTS disk_io (READ DISK default, WRITE DISK default);
CREATE RESOURCE IF NOT EXISTS cpu (MASTER THREAD, WORKER THREAD);
CREATE WORKLOAD IF NOT EXISTS all;
CREATE WORKLOAD IF NOT EXISTS background IN all SETTINGS weight = 9;
CREATE WORKLOAD IF NOT EXISTS replication_reads IN all SETTINGS weight = 1, max_bytes_per_second = 50000000, max_concurrent_threads = 2;

-- Bounds for replication reads. MAX/CONST constraints mean the client can only make queries
-- more restrictive, never less.
CREATE SETTINGS PROFILE IF NOT EXISTS repl_reader_profile SETTINGS
    readonly = 2,
    max_threads = 2 MAX 2,
    max_memory_usage = 4000000000 MAX 4000000000,
    max_execution_time = 900 MAX 900,
    max_bytes_to_read = 50000000000 MAX 50000000000,
    max_network_bandwidth = 50000000 MAX 50000000,          -- native protocol only; does not throttle HTTP
    max_local_read_bandwidth = 50000000 MAX 50000000,       -- throttles disk reads, effective for HTTP reads
    max_network_bandwidth_for_user = 100000000 MAX 100000000,
    max_concurrent_queries_for_user = 2 MAX 2,
    use_uncompressed_cache = 0 CONST,
    min_bytes_to_use_direct_io = 10000000 MAX 10000000,
    enable_filesystem_cache = 0 CONST,
    read_from_filesystem_cache_if_exists_otherwise_bypass_cache = 1 CONST,
    force_index_by_date = 1,
    workload = 'replication_reads' CONST;

CREATE USER IF NOT EXISTS repl_reader IDENTIFIED WITH sha256_password BY '<reader-secret>'
    HOST IP '10.20.0.0/16'
    SETTINGS PROFILE repl_reader_profile;
GRANT SELECT ON trading.* TO repl_reader;
GRANT SELECT ON crm.* TO repl_reader;
GRANT SELECT ON refdata.* TO repl_reader;
GRANT SELECT ON system.parts TO repl_reader;
GRANT SELECT ON system.tables TO repl_reader;
GRANT SELECT ON system.columns TO repl_reader;
GRANT SELECT ON system.replicas TO repl_reader;

CREATE QUOTA IF NOT EXISTS repl_reader_quota FOR INTERVAL 1 hour
    MAX queries = 2000, read_rows = 20000000000, read_bytes = 2000000000000, execution_time = 7200
    TO repl_reader;

-- The guard: reads system tables and may kill replication queries only (enforced in code and tests).
-- No readonly here: readonly forbids KILL QUERY on other users' queries. Its grants (SELECT on system
-- tables, SHOW TABLES, KILL QUERY) are all it can do.
CREATE SETTINGS PROFILE IF NOT EXISTS repl_guard_profile SETTINGS
    max_threads = 1 MAX 1, max_execution_time = 10 MAX 10, max_memory_usage = 200000000 MAX 200000000;
CREATE USER IF NOT EXISTS repl_guard IDENTIFIED WITH sha256_password BY '<guard-secret>'
    HOST IP '10.20.0.0/16'
    SETTINGS PROFILE repl_guard_profile;
GRANT SELECT ON system.processes TO repl_guard;
GRANT SELECT ON system.metrics TO repl_guard;
GRANT SELECT ON system.events TO repl_guard;
GRANT SELECT ON system.asynchronous_metrics TO repl_guard;
GRANT SELECT ON system.merges TO repl_guard;
GRANT SELECT ON system.mutations TO repl_guard;
GRANT SELECT ON system.parts TO repl_guard;
GRANT SELECT ON system.replication_queue TO repl_guard;
GRANT SELECT ON system.replicas TO repl_guard;
GRANT SELECT ON system.replicated_fetches TO repl_guard;
GRANT SELECT ON system.user_directories TO repl_guard;
-- system.replicas / system.replication_queue only list tables the user may SHOW. Without this the guard
-- would see no replication at all; it treats that as UNKNOWN (fail closed), but grant it on every DR node.
GRANT SHOW TABLES ON *.* TO repl_guard;
GRANT KILL QUERY ON *.* TO repl_guard;
