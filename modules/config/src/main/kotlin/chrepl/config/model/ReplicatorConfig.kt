package chrepl.config.model

/**
 * Root of the replicator configuration (SPEC §9.2). Field names map to snake_case YAML keys;
 * map keys (target names, domain names, column names) are kept verbatim.
 */
data class ReplicatorConfig(
    val version: Int,
    val activeDc: ActiveDc,
    val sources: Map<String, Source>,
    val targets: Map<String, Target>,
    val guard: Guard,
    val masking: Masking,
    val tables: List<TableSpec>,
    val datasets: List<Dataset> = emptyList(),
    val continuous: List<ContinuousJob> = emptyList(),
    val scheduling: Scheduling = Scheduling(),
    val access: List<AccessRule> = emptyList(),
)

/** Hosts and CIDRs of the active production DC, which the tool must never contact (SPEC §1.3). */
data class ActiveDc(
    val name: String,
    val hosts: List<String> = emptyList(),
    val cidrs: List<String> = emptyList(),
)

data class Credentials(
    val user: String,
    val password: SecretRef,
)

// ---------------------------------------------------------------------------- sources

data class Source(
    /** Must be `dr`; `test` is accepted for Testcontainers/dev configs only. */
    val dc: String,
    val clusterName: String,
    val clickhouseVersion: String? = null,
    val tls: Boolean = true,
    val reader: Credentials,
    val guard: Credentials,
    val shards: List<SourceShard>,
    val failClosedScope: FailClosedScope = FailClosedScope.CLUSTER,
    val dcRoleCheck: DcRoleCheck = DcRoleCheck(),
)

data class SourceShard(
    val name: String,
    /** The shard's single DR replica: both read and watch host. */
    val host: HostPort,
)

enum class FailClosedScope { CLUSTER, SHARD }

data class DcRoleCheck(
    val foreignActivity: Boolean = true,
    val ignoredUsers: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------- targets

data class Target(
    val hosts: List<HostPort>,
    val clusterName: String,
    val shards: Int = 1,
    val shardMapping: ShardMapping,
    val tls: Boolean = true,
    val writer: Credentials,
    val environmentMarker: String,
    val zookeeperPathTemplate: String? = null,
    val recentCopyWarning: ConfigDuration? = null,
    val tableOverrides: TableOverrides? = null,
    /** Drop COB partitions whose newest COB date is older than this (SPEC §11); null keeps everything. */
    val retention: ConfigDuration? = null,
)

enum class ShardMapping { SAME_SHARDS, DISTRIBUTED, SINGLE }

data class TableOverrides(
    val ttl: String? = null,
    val storagePolicy: String? = null,
)

// ---------------------------------------------------------------------------- guard

data class Guard(
    val pollInterval: ConfigDuration,
    val staleAfter: ConfigDuration,
    val stableWindow: ConfigDuration,
    val releaseRatio: Double = 0.8,
    val thresholds: Thresholds,
    val concurrency: Concurrency,
    val preemption: Preemption = Preemption(),
    val circuitBreaker: CircuitBreaker,
    val blackoutWindows: List<BlackoutWindow> = emptyList(),
)

data class Thresholds(
    val replication: ReplicationThresholds,
    val host: HostThresholds,
    val background: BackgroundThresholds,
)

data class ReplicationThresholds(
    val maxReplicaDelay: ConfigDuration,
    val maxQueueSize: Int,
    val maxQueueOldestAge: ConfigDuration,
    val maxStuckEntries: Int,
    val maxInflightFetches: Int,
    val blockOnReadonly: Boolean = true,
)

data class HostThresholds(
    val maxCpuUtilisation: Double,
    val maxIoWait: Double,
    val maxMemoryRatio: Double,
)

data class BackgroundThresholds(
    val maxActiveMerges: Int,
    val maxMutationBytes: DataSize,
)

data class Concurrency(
    val global: Int,
    val perHost: Int,
)

data class Preemption(
    /** Exists only so that `enabled: false` can be rejected with a clear message. */
    val enabled: Boolean = true,
    val killGrace: ConfigDuration = ConfigDuration.parse("5s"),
)

data class CircuitBreaker(
    val maxPreemptions: Int,
    val within: ConfigDuration,
    val openFor: ConfigDuration,
)

data class BlackoutWindow(
    val name: String,
    val cron: String,
    val duration: ConfigDuration,
    val timezone: String,
)

// ---------------------------------------------------------------------------- masking

data class Masking(
    val realm: String,
    val key: MaskingKey,
    val dictionaries: Map<String, Dictionary> = emptyMap(),
    val domains: Map<String, Domain>,
)

data class MaskingKey(
    /** `static` (key material from a secret) or `fortanix` (Fortanix DSM, BYOK; SPEC §6.2). */
    val provider: String,
    /** The key material for `static`. */
    val ref: SecretRef? = null,
    val version: Int,
    val fortanix: FortanixKey? = null,
)

/**
 * Where the master key comes from in Fortanix DSM. Either envelope mode, where a non-exportable
 * [kek] decrypts [wrappedKey], or export mode, which reads the exportable HMAC security object
 * [keyName]. Workers authenticate with an app API key and fetch the key once at start-up.
 */
data class FortanixKey(
    /** DSM base URL, e.g. https://eu.smartkey.io; https is required except for localhost. */
    val endpoint: String,
    /** The DSM app API key (base64 `app-id:secret`), as a secret reference. */
    val apiKey: SecretRef,
    /** Name of the KEK security object (envelope mode). */
    val kek: String? = null,
    /** The wrapped master key (JSON from `clickhouse-repl dsm-wrap`), as a secret reference. */
    val wrappedKey: SecretRef? = null,
    /** Name of an exportable HMAC security object holding the master key (export mode). */
    val keyName: String? = null,
)

data class Dictionary(
    val file: String,
)

enum class Strategy { HASH, TOKENIZE, FPE_FF1, EMAIL, DICTIONARY, DATE_SHIFT, NUMERIC_NOISE, PARTIAL, NULL, CONSTANT }

enum class Normaliser { TRIM, LOWER, NFC }

enum class EmailLocalPart { TOKENIZE, HASH }

enum class EmailDomainMode { KEEP, MAP_TO_EXAMPLE, TOKENIZE }

enum class HashEncoding { HEX, BASE64 }

/** A masking domain. Which options apply depends on [strategy]; the validator rejects the rest. */
data class Domain(
    val strategy: Strategy,
    val normalise: List<Normaliser> = emptyList(),
    val collisionCheck: Boolean = false,
    val approvedBy: String? = null,
    // hash
    val encoding: HashEncoding? = null,
    val length: Int? = null,
    // fpe_ff1
    val alphabet: String? = null,
    /** Digits only: values are integers in [0, 10^width). */
    val width: Int? = null,
    // email
    val localPart: EmailLocalPart? = null,
    val domain: EmailDomainMode? = null,
    // dictionary
    val dictionary: String? = null,
    // date_shift
    val maxDays: Int? = null,
    // numeric_noise
    val relative: Double? = null,
    // partial
    val keepFirst: Int? = null,
    val keepLast: Int? = null,
    val maskChar: String? = null,
    // constant
    val value: String? = null,
)

// ---------------------------------------------------------------------------- tables

data class TableSpec(
    /** `database.table` on the source. */
    val source: String,
    val cobColumn: String? = null,
    val fullCopy: Boolean = false,
    val maxFullCopyBytes: DataSize? = null,
    /** Declared partition key expression; compared with DR by the online check (PLAN M3). */
    val partitionKey: String? = null,
    /** Declared sharding key expression of the Distributed table. */
    val shardingKey: String? = null,
    val mvMode: MvMode = MvMode.BASE_ONLY,
    val chunk: ChunkSettings? = null,
    val classification: Classification? = null,
    val approvedBy: String? = null,
    val unlistedColumns: UnlistedColumns = UnlistedColumns.FAIL,
    val columns: Map<String, ColumnPolicy> = emptyMap(),
)

enum class MvMode { BASE_ONLY, TARGETS_ONLY, NONE }

enum class Classification { NON_PII }

enum class UnlistedColumns { FAIL, PASS }

data class ChunkSettings(
    val maxBytes: DataSize? = null,
    val maxSeconds: Int? = null,
)

data class Dataset(
    val name: String,
    val description: String? = null,
    val tables: List<String>,
)

// ---------------------------------------------------------------------------- continuous

enum class ContinuousMode { APPEND_WATERMARK, PARTITION_FINGERPRINT, SCHEDULED_FULL_REFRESH }

enum class Dedupe { REPLACE_PARTITION, REPLACING_MERGE_TREE }

enum class Verify { NONE, CHECKSUM }

data class ContinuousJob(
    val name: String,
    val table: String? = null,
    val tables: List<String> = emptyList(),
    val targets: List<String>,
    val mode: ContinuousMode,
    val watermarkColumn: String? = null,
    val interval: ConfigDuration? = null,
    val overlap: ConfigDuration? = null,
    val dedupe: Dedupe? = null,
    val onlyRecentPartitions: Int? = null,
    val syncDrops: Boolean? = null,
    val verify: Verify? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val lagSlo: ConfigDuration? = null,
    val lagAlertAfter: ConfigDuration? = null,
) {
    /** Tables this job covers, whether given as `table` or `tables`. */
    val allTables: List<String> get() = listOfNotNull(table) + tables
}

// ---------------------------------------------------------------------------- scheduling and access

data class Scheduling(
    val queueWeights: QueueWeights = QueueWeights(),
    val maxRunningJobsPerUser: Int = 2,
)

data class QueueWeights(
    val continuous: Int = 3,
    val adhoc: Int = 1,
)

enum class Role { VIEWER, REQUESTER, OPERATOR, ADMIN }

data class AccessRule(
    val group: String,
    val role: Role,
    val datasets: List<String> = emptyList(),
    val tables: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val quota: Quota? = null,
)

data class Quota(
    val maxBytesPerJob: DataSize,
    val maxBytesPerDay: DataSize,
    val maxCobDatesPerJob: Int,
    val maxConcurrentJobs: Int,
)
