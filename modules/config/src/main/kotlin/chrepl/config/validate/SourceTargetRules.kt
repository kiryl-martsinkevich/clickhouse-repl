package chrepl.config.validate

import chrepl.config.model.ShardMapping

private val RESERVED_ENVIRONMENTS = setOf("prod", "production", "live", "dr")

internal fun RuleContext.checkSources() {
    if (config.sources.size != 1) {
        error(Code.SOURCES_COUNT, "sources", "exactly one source (the DR cluster) is supported, found ${config.sources.size}")
    }
    for ((name, source) in config.sources) {
        val path = "sources.$name"
        when (source.dc) {
            "dr" -> {}

            "test" -> {
                warn(Code.SOURCE_DC_TEST, "$path.dc", "dc: test is for Testcontainers/dev configs only and is refused by the guard")
            }

            else -> {
                error(Code.SOURCE_DC, "$path.dc", "sources must be the passive DR DC (dc: dr), got '${source.dc}'")
            }
        }
        if (source.shards.isEmpty()) error(Code.SOURCE_SHARDS, "$path.shards", "at least one shard is required")
        for (dup in source.shards.duplicates { it.name }) error(Code.SOURCE_SHARDS, "$path.shards", "duplicate shard name '$dup'")
        source.shards.forEachIndexed { i, shard ->
            when {
                shard.host != null && shard.replicas.isNotEmpty() ->
                    error(Code.SOURCE_SHARDS, "$path.shards[$i]", "give either host (one DR replica) or replicas, not both")

                shard.host == null && shard.replicas.isEmpty() ->
                    error(Code.SOURCE_SHARDS, "$path.shards[$i]", "give the shard's DR replica as host, or its DR replicas as replicas")
            }
        }
        source.keeper?.let { keeper ->
            if (keeper.hosts.isEmpty()) error(Code.SOURCE_SHARDS, "$path.keeper.hosts", "list the DR Keeper nodes, or remove keeper")
            for (dup in keeper.hosts.duplicates {
                it.toString()
            }) {
                error(Code.DUPLICATE_HOST, "$path.keeper.hosts", "Keeper node '$dup' is listed twice")
            }
        }
        if (source.reader.user == source.guard.user) {
            error(Code.SOURCE_USERS, "$path.guard.user", "reader and guard must be different users (only the guard may KILL QUERY)")
        }
        for ((role, user) in listOf("reader" to source.reader.user, "guard" to source.guard.user)) {
            if (user == "default") error(Code.SOURCE_USERS, "$path.$role.user", "use the dedicated repl_* users, not 'default'")
        }
        if (!source.dcRoleCheck.foreignActivity) {
            error(
                Code.DC_ROLE_CHECK_DISABLED,
                "$path.dc_role_check.foreign_activity",
                "foreign-activity detection is the automatic DR_ACTIVE trigger and cannot be disabled (SPEC §4.6)",
            )
        }
        checkSecret(source.reader.password, "$path.reader.password")
        checkSecret(source.guard.password, "$path.guard.password")
    }
}

internal fun RuleContext.checkTargets() {
    if (config.targets.isEmpty()) error(Code.TARGET_HOSTS, "targets", "at least one target is required")
    for ((name, target) in config.targets) {
        val path = "targets.$name"
        if (target.hosts.isEmpty()) error(Code.TARGET_HOSTS, "$path.hosts", "at least one host is required")
        if (target.shards < 1) error(Code.SHARD_MAPPING, "$path.shards", "shards must be at least 1")
        if (target.replicas < 1) error(Code.TARGET_HOSTS, "$path.replicas", "replicas must be at least 1")
        val expected = target.shards * target.replicas
        if (target.replicas > 1 && target.hosts.size != expected) {
            error(
                Code.TARGET_HOSTS,
                "$path.hosts",
                "${target.shards} shard(s) × ${target.replicas} replicas need exactly $expected hosts (shard-major), found ${target.hosts.size}",
            )
        } else if (target.hosts.size < target.shards) {
            error(
                Code.TARGET_HOSTS,
                "$path.hosts",
                "${target.shards} shards need at least ${target.shards} hosts, found ${target.hosts.size}",
            )
        }
        if (target.replicas > 1 && target.zookeeperPathTemplate == null) {
            error(
                Code.TARGET_HOSTS,
                "$path.zookeeper_path_template",
                "a target with replicas needs zookeeper_path_template, so its tables are ReplicatedMergeTree",
            )
        }
        when (target.shardMapping) {
            ShardMapping.SAME_SHARDS -> {
                val sourceShards = sourceShardCount
                if (sourceShards != null && target.shards != sourceShards) {
                    error(
                        Code.SHARD_MAPPING,
                        "$path.shard_mapping",
                        "same_shards needs $sourceShards target shards (like the source), found ${target.shards}",
                    )
                }
            }

            ShardMapping.SINGLE -> {
                if (target.shards != 1) error(Code.SHARD_MAPPING, "$path.shard_mapping", "single needs shards: 1, found ${target.shards}")
            }

            ShardMapping.DISTRIBUTED -> {}
        }
        val marker = target.environmentMarker
        when {
            marker.lowercase() in RESERVED_ENVIRONMENTS || name.lowercase() in RESERVED_ENVIRONMENTS -> {
                error(Code.TARGET_MARKER, "$path.environment_marker", "a target can never be a production or DR environment")
            }

            marker != name -> {
                error(Code.TARGET_MARKER, "$path.environment_marker", "environment marker '$marker' must equal the target name '$name'")
            }
        }
        target.retention?.let {
            if (it.value < java.time.Duration.ofDays(1)) error(Code.INVALID_VALUE, "$path.retention", "retention must be at least 1d")
        }
        target.recentCopyWarning?.let {
            if (it.value.isZero) error(Code.INVALID_VALUE, "$path.recent_copy_warning", "must be positive")
        }
        checkSecret(target.writer.password, "$path.writer.password")
    }
}
