package chrepl.config.validate

import chrepl.config.model.Role

internal fun RuleContext.checkAccess() {
    val datasetNames = config.datasets.map { it.name }.toSet()
    for (dup in config.access.duplicates { it.group }) error(Code.DUPLICATE_GROUP, "access", "group '$dup' has more than one rule")
    config.access.forEachIndexed { i, rule ->
        val path = "access[$i]"
        if (rule.group.isBlank()) error(Code.INVALID_NAME, "$path.group", "group must not be blank")
        rule.datasets.forEachIndexed { j, name ->
            if (name !in datasetNames) error(Code.UNKNOWN_DATASET, "$path.datasets[$j]", "unknown dataset '$name'")
        }
        rule.tables.forEachIndexed { j, name ->
            if (name !in tableNames) error(Code.UNKNOWN_TABLE, "$path.tables[$j]", "table '$name' has no table spec")
        }
        rule.targets.forEachIndexed { j, name ->
            if (name !in config.targets) error(Code.UNKNOWN_TARGET, "$path.targets[$j]", "unknown target '$name'")
        }
        if (rule.role == Role.REQUESTER) {
            if (rule.datasets.isEmpty() && rule.tables.isEmpty()) {
                error(Code.ACCESS_SCOPE, path, "a requester rule needs datasets and/or tables")
            }
            if (rule.targets.isEmpty()) error(Code.ACCESS_SCOPE, "$path.targets", "a requester rule needs at least one target")
            if (rule.quota == null) error(Code.QUOTA_REQUIRED, "$path.quota", "requesters need a quota (there is no approval step)")
        }
        rule.quota?.let { quota ->
            if (quota.maxBytesPerJob.bytes <= 0 ||
                quota.maxBytesPerDay.bytes <= 0 ||
                quota.maxCobDatesPerJob < 1 ||
                quota.maxConcurrentJobs < 1
            ) {
                error(Code.QUOTA_INVALID, "$path.quota", "all quota values must be positive")
            } else if (quota.maxBytesPerJob > quota.maxBytesPerDay) {
                error(Code.QUOTA_INVALID, "$path.quota.max_bytes_per_job", "max_bytes_per_job exceeds max_bytes_per_day")
            }
        }
    }
    val scheduling = config.scheduling
    if (scheduling.queueWeights.continuous < 1 || scheduling.queueWeights.adhoc < 1) {
        error(Code.SCHEDULING, "scheduling.queue_weights", "queue weights must be at least 1")
    }
    if (scheduling.maxRunningJobsPerUser < 1) error(Code.SCHEDULING, "scheduling.max_running_jobs_per_user", "must be at least 1")
}
