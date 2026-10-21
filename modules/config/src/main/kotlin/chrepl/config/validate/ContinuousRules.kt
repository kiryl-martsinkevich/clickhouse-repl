package chrepl.config.validate

import chrepl.config.cron.CronValidator
import chrepl.config.model.ContinuousJob
import chrepl.config.model.ContinuousMode
import chrepl.config.model.UnlistedColumns
import java.time.ZoneId

internal fun RuleContext.checkDatasets() {
    for (dup in config.datasets.duplicates { it.name }) error(Code.DUPLICATE_NAME, "datasets", "dataset '$dup' is defined more than once")
    config.datasets.forEachIndexed { i, dataset ->
        val path = "datasets[$i]"
        if (!NAME_PATTERN.matches(dataset.name)) error(Code.INVALID_NAME, "$path.name", "use lower-case letters, digits, '-' and '_'")
        if (dataset.tables.isEmpty()) error(Code.UNKNOWN_TABLE, "$path.tables", "a dataset needs at least one table")
        dataset.tables.forEachIndexed { j, table ->
            if (table !in tableNames) error(Code.UNKNOWN_TABLE, "$path.tables[$j]", "table '$table' has no table spec")
        }
    }
}

internal fun RuleContext.checkContinuous() {
    for (dup in config.continuous.duplicates {
        it.name
    }) {
        error(Code.DUPLICATE_NAME, "continuous", "continuous job '$dup' is defined more than once")
    }
    config.continuous.forEachIndexed { i, job -> checkJob("continuous[$i]", job) }
}

/** Options each mode requires and accepts, besides the common ones. */
private val MODE_OPTIONS: Map<ContinuousMode, Pair<Set<String>, Set<String>>> =
    mapOf(
        ContinuousMode.APPEND_WATERMARK to
            (setOf("watermark_column", "interval", "overlap") to setOf("dedupe", "only_recent_partitions")),
        ContinuousMode.PARTITION_FINGERPRINT to
            (setOf("interval") to setOf("only_recent_partitions", "sync_drops", "verify")),
        ContinuousMode.SCHEDULED_FULL_REFRESH to
            (setOf("cron", "timezone") to setOf("only_recent_partitions")),
    )

private fun RuleContext.checkJob(
    path: String,
    job: ContinuousJob,
) {
    if (!NAME_PATTERN.matches(job.name)) error(Code.INVALID_NAME, "$path.name", "use lower-case letters, digits, '-' and '_'")
    if ((job.table == null) == job.tables.isEmpty()) {
        error(Code.CONTINUOUS_TABLES, path, "set either table or tables")
    }
    val specs =
        job.allTables.mapNotNull { name ->
            val spec = config.tables.firstOrNull { it.source == name }
            if (spec == null) error(Code.UNKNOWN_TABLE, path, "table '$name' has no table spec")
            spec
        }
    if (job.targets.isEmpty()) error(Code.UNKNOWN_TARGET, "$path.targets", "at least one target is required")
    job.targets.forEachIndexed { j, target ->
        if (target !in config.targets) error(Code.UNKNOWN_TARGET, "$path.targets[$j]", "unknown target '$target'")
    }

    val (required, optional) = MODE_OPTIONS.getValue(job.mode)
    val present = presentOptions(job)
    val mode = job.mode.name.lowercase()
    for (missing in required - present) error(Code.CONTINUOUS_OPTION_MISSING, "$path.$missing", "mode $mode requires '$missing'")
    for (extra in present - required - optional) {
        error(Code.CONTINUOUS_OPTION_UNSUPPORTED, "$path.$extra", "'$extra' does not apply to mode $mode")
    }

    job.watermarkColumn?.let { column ->
        for (spec in specs) {
            when {
                column !in spec.columns && spec.unlistedColumns == UnlistedColumns.FAIL -> {
                    error(Code.WATERMARK_COLUMN, "$path.watermark_column", "'$column' is not a column of ${spec.source}")
                }

                !spec.passesThrough(column) -> {
                    error(Code.WATERMARK_COLUMN, "$path.watermark_column", "watermark column '$column' of ${spec.source} must be pass")
                }
            }
        }
    }
    job.interval?.let {
        if (it < config.guard.stableWindow) {
            error(
                Code.INTERVAL_TOO_SHORT,
                "$path.interval",
                "interval $it is shorter than the guard stable_window ${config.guard.stableWindow}",
            )
        }
    }
    job.onlyRecentPartitions?.let { if (it < 1) error(Code.INVALID_VALUE, "$path.only_recent_partitions", "must be at least 1") }
    job.cron?.let { cron -> CronValidator.validate(cron)?.let { error(Code.INVALID_CRON, "$path.cron", it) } }
    job.timezone?.let { checkTimezone("$path.timezone", it) }
    if (job.lagAlertAfter != null && job.lagSlo == null) {
        error(Code.CONTINUOUS_OPTION_MISSING, "$path.lag_slo", "lag_alert_after needs lag_slo")
    }
}

private fun presentOptions(job: ContinuousJob): Set<String> =
    buildSet {
        if (job.watermarkColumn != null) add("watermark_column")
        if (job.interval != null) add("interval")
        if (job.overlap != null) add("overlap")
        if (job.dedupe != null) add("dedupe")
        if (job.onlyRecentPartitions != null) add("only_recent_partitions")
        if (job.syncDrops != null) add("sync_drops")
        if (job.verify != null) add("verify")
        if (job.cron != null) add("cron")
        if (job.timezone != null) add("timezone")
    }

internal fun RuleContext.checkTimezone(
    path: String,
    zone: String,
) {
    if (runCatching { ZoneId.of(zone) }.isFailure) error(Code.INVALID_TIMEZONE, path, "unknown timezone '$zone'")
}
