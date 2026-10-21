package chrepl.config.validate

import chrepl.config.expr.Identifiers
import chrepl.config.model.Classification
import chrepl.config.model.ColumnPolicy
import chrepl.config.model.ShardMapping
import chrepl.config.model.Strategy
import chrepl.config.model.TableSpec
import chrepl.config.model.UnlistedColumns

/** Per-query read cap of the repl_reader profile (SPEC §4.2) and its execution-time cap. */
internal const val L1_MAX_BYTES_TO_READ = 50_000_000_000L
internal const val L1_MAX_EXECUTION_SECONDS = 900

private val TABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*$")

internal fun RuleContext.checkTables() {
    for (dup in config.tables.duplicates { it.source }) {
        error(Code.DUPLICATE_TABLE, "tables", "table '$dup' is configured more than once")
    }
    config.tables.forEachIndexed { i, table -> checkTable("tables[$i]", table) }
}

/** True when [column] is copied unchanged: listed as `pass`, or unlisted in an approved non-PII table. */
internal fun TableSpec.passesThrough(column: String): Boolean =
    when (val policy = columns[column]) {
        null -> unlistedColumns == UnlistedColumns.PASS
        else -> !policy.isMasked
    }

private fun RuleContext.checkTable(
    path: String,
    table: TableSpec,
) {
    if (!TABLE_NAME.matches(table.source)) {
        error(Code.TABLE_NAME, "$path.source", "expected database.table, got '${table.source}'")
    }

    // COB slice or full copy, never both (SPEC §5.3)
    val cob = table.cobColumn
    when {
        cob != null && table.fullCopy -> {
            error(Code.COB_OR_FULL_COPY, path, "set either cob_column or full_copy: true, not both")
        }

        cob == null && !table.fullCopy -> {
            error(Code.COB_OR_FULL_COPY, path, "set cob_column (COB-sliced copies) or full_copy: true (small tables)")
        }
    }
    if (table.fullCopy) {
        val cap = table.maxFullCopyBytes
        if (cap == null) {
            error(Code.FULL_COPY_CAP, "$path.max_full_copy_bytes", "full_copy tables need a size cap")
        } else if (cap.bytes <= 0 || cap.bytes > L1_MAX_BYTES_TO_READ) {
            error(Code.FULL_COPY_CAP, "$path.max_full_copy_bytes", "cap must be positive and at most the L1 read limit (50GB)")
        }
    } else if (table.maxFullCopyBytes != null) {
        error(Code.FULL_COPY_CAP, "$path.max_full_copy_bytes", "max_full_copy_bytes applies only to full_copy tables")
    }

    // Classification: every column needs a policy unless the table is approved non-PII (SPEC §6.1)
    val approvedNonPii = table.classification == Classification.NON_PII && !table.approvedBy.isNullOrBlank()
    if (table.classification == Classification.NON_PII && table.approvedBy.isNullOrBlank()) {
        error(Code.APPROVAL_REQUIRED, "$path.approved_by", "classification: non_pii needs approved_by")
    }
    if (table.unlistedColumns == UnlistedColumns.PASS && !approvedNonPii) {
        error(
            Code.UNCLASSIFIED_COLUMNS,
            "$path.unlisted_columns",
            "unlisted_columns: pass is only allowed with classification: non_pii and approved_by",
        )
    }
    if (table.columns.isEmpty() && table.unlistedColumns == UnlistedColumns.FAIL) {
        error(Code.UNCLASSIFIED_COLUMNS, "$path.columns", "no column policies; every column must be listed as pass or masked")
    }
    for ((column, policy) in table.columns) checkPolicy("$path.columns.$column", policy, table)

    if (cob != null) {
        when {
            cob !in table.columns && table.unlistedColumns == UnlistedColumns.FAIL -> {
                error(Code.COB_COLUMN, "$path.cob_column", "COB column '$cob' is not listed in columns")
            }

            !table.passesThrough(cob) -> {
                error(Code.COB_MASKED, "$path.columns.$cob", "the COB column must be pass; masking it would break COB filtering")
            }
        }
    }

    checkKeys(path, table)

    table.chunk?.let { chunk ->
        chunk.maxBytes?.let {
            if (it.bytes <= 0 || it.bytes > L1_MAX_BYTES_TO_READ) {
                error(Code.CHUNK_LIMITS, "$path.chunk.max_bytes", "chunk size must be positive and at most the L1 read limit (50GB)")
            }
        }
        chunk.maxSeconds?.let {
            if (it !in 1..L1_MAX_EXECUTION_SECONDS) {
                error(
                    Code.CHUNK_LIMITS,
                    "$path.chunk.max_seconds",
                    "chunk duration must be 1..$L1_MAX_EXECUTION_SECONDS s (L1 max_execution_time)",
                )
            }
        }
    }
}

private fun RuleContext.checkKeys(
    path: String,
    table: TableSpec,
) {
    val masked = table.columns.filterValues { it.isMasked }.keys
    val partitionKey = table.partitionKey
    if (partitionKey == null) {
        if (table.cobColumn != null) {
            warn(
                Code.PARTITION_KEY_UNDECLARED,
                "$path.partition_key",
                "declare partition_key so masked-key and COB checks run offline (the online check compares it with DR)",
            )
        }
    } else {
        val identifiers = Identifiers.of(partitionKey)
        for (column in identifiers.intersect(masked)) {
            error(Code.PARTITION_KEY_MASKED, "$path.columns.$column", "column '$column' is in the partition key and must not be masked")
        }
        val cob = table.cobColumn
        if (cob != null && cob !in identifiers) {
            warn(
                Code.COB_NOT_IN_PARTITION_KEY,
                "$path.partition_key",
                "COB column '$cob' is not in the partition key; COB reads cannot prune partitions",
            )
        }
    }
    val shardingKey = table.shardingKey ?: return
    val sameShardTargets = config.targets.filterValues { it.shardMapping == ShardMapping.SAME_SHARDS }.keys
    if (sameShardTargets.isEmpty()) return
    for (column in Identifiers.of(shardingKey).intersect(masked)) {
        warn(
            Code.SHARDING_KEY_MASKED,
            "$path.sharding_key",
            "masked column '$column' is in the sharding key; with same_shards targets (${sameShardTargets.joinToString()}) " +
                "rows stay on their original shard, which no longer matches the masked value — consider shard_mapping: distributed",
        )
    }
}

private fun RuleContext.checkPolicy(
    path: String,
    policy: ColumnPolicy,
    table: TableSpec,
) {
    when (policy) {
        ColumnPolicy.Pass -> {}

        is ColumnPolicy.Mask -> {
            val domain = config.masking.domains[policy.domain]
            if (domain == null) {
                error(Code.UNKNOWN_DOMAIN, path, "unknown masking domain '${policy.domain}'")
                return
            }
            val entity = policy.entityColumn
            when {
                entity == null && domain.strategy == Strategy.DATE_SHIFT -> {
                    error(Code.ENTITY_COLUMN_REQUIRED, path, "date_shift needs entity_column so all dates of one entity shift together")
                }

                entity != null && domain.strategy !in ENTITY_STRATEGIES -> {
                    error(
                        Code.ENTITY_COLUMN,
                        "$path.entity_column",
                        "entity_column does not apply to strategy ${domain.strategy.name.lowercase()}",
                    )
                }

                entity != null && entity !in table.columns -> {
                    error(Code.ENTITY_COLUMN, "$path.entity_column", "entity column '$entity' is not a column of ${table.source}")
                }
            }
        }

        is ColumnPolicy.MapPolicy -> {
            policy.mapKeys?.let { checkPolicy("$path.map_keys", it, table) }
            for ((key, child) in policy.mapValues) checkPolicy("$path.map_values.$key", child, table)
            checkPolicy("$path.default", policy.default, table)
        }

        is ColumnPolicy.JsonPaths -> {
            for ((jsonPath, child) in policy.paths) {
                if (!jsonPath.startsWith("$.") || jsonPath.length < 3) {
                    error(Code.JSON_PATH, "$path.json_paths", "JSON path '$jsonPath' must start with \$.")
                }
                checkPolicy("$path.json_paths.$jsonPath", child, table)
            }
            checkPolicy("$path.default", policy.default, table)
        }
    }
}
