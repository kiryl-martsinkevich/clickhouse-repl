package chrepl.config.validate

enum class Severity { ERROR, WARNING }

/** Stable identifiers for every problem the loader or validator can report. */
enum class Code {
    // loading and binding
    FILE_NOT_FOUND,
    YAML_SYNTAX,
    DUPLICATE_KEY,
    UNSUPPORTED_VERSION,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_VALUE,

    // active-DC isolation (SPEC §1.3, §9.3)
    ACTIVE_DC_EMPTY,
    INVALID_CIDR,
    ACTIVE_DC_HOST,
    ACTIVE_DC_CIDR,
    DNS_UNRESOLVED,
    TARGET_IS_SOURCE,
    DUPLICATE_HOST,

    // sources and targets
    SOURCES_COUNT,
    SOURCE_DC,
    SOURCE_DC_TEST,
    SOURCE_SHARDS,
    SOURCE_USERS,
    DC_ROLE_CHECK_DISABLED,
    TARGET_HOSTS,
    TARGET_MARKER,
    SHARD_MAPPING,
    SECRET_LITERAL,
    SECRET_SCHEME,
    SECRET_UNRESOLVED,

    // masking
    REALM,
    KEY_PROVIDER,
    KEY_REF,
    KEY_TOO_SHORT,
    KEY_VERSION,
    UNKNOWN_DICTIONARY,
    DICTIONARY_FILE,
    STRATEGY_OPTION_MISSING,
    STRATEGY_OPTION_UNSUPPORTED,
    STRATEGY_OPTION_INVALID,

    // tables
    TABLE_NAME,
    DUPLICATE_TABLE,
    COB_OR_FULL_COPY,
    FULL_COPY_CAP,
    APPROVAL_REQUIRED,
    UNCLASSIFIED_COLUMNS,
    UNKNOWN_DOMAIN,
    ENTITY_COLUMN,
    ENTITY_COLUMN_REQUIRED,
    JSON_PATH,
    COB_COLUMN,
    COB_MASKED,
    PARTITION_KEY_MASKED,
    PARTITION_KEY_UNDECLARED,
    COB_NOT_IN_PARTITION_KEY,
    SHARDING_KEY_MASKED,
    CHUNK_LIMITS,

    // datasets and continuous jobs
    DUPLICATE_NAME,
    INVALID_NAME,
    UNKNOWN_TABLE,
    UNKNOWN_TARGET,
    UNKNOWN_DATASET,
    CONTINUOUS_TABLES,
    CONTINUOUS_OPTION_MISSING,
    CONTINUOUS_OPTION_UNSUPPORTED,
    WATERMARK_COLUMN,
    INTERVAL_TOO_SHORT,
    INVALID_CRON,
    INVALID_TIMEZONE,

    // guard
    PREEMPTION_DISABLED,
    UNSAFE_GUARD_SETTING,
    GUARD_TIMING,
    GUARD_THRESHOLD,
    GUARD_CONCURRENCY,

    // access and scheduling
    DUPLICATE_GROUP,
    ACCESS_SCOPE,
    QUOTA_REQUIRED,
    QUOTA_INVALID,
    SCHEDULING,
}

data class Issue(
    val severity: Severity,
    val code: Code,
    /** Location in the config, e.g. `tables[0].columns.customer_id`; empty for the whole file. */
    val path: String,
    val message: String,
)

data class ValidationReport(
    val issues: List<Issue>,
) {
    val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()

    operator fun plus(other: ValidationReport) = ValidationReport(issues + other.issues)
}

/** Accumulates issues while rules run. */
class IssueCollector {
    private val issues = mutableListOf<Issue>()

    fun error(
        code: Code,
        path: String,
        message: String,
    ) {
        issues += Issue(Severity.ERROR, code, path, message)
    }

    fun warn(
        code: Code,
        path: String,
        message: String,
    ) {
        issues += Issue(Severity.WARNING, code, path, message)
    }

    fun report(): ValidationReport = ValidationReport(issues.toList())
}
