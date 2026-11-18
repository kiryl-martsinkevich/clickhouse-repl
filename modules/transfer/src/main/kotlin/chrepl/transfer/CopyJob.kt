package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Endpoint
import chrepl.clickhouse.Sql
import chrepl.config.model.HostPort
import chrepl.config.model.MvMode
import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import chrepl.config.model.ShardMapping
import chrepl.config.model.TableSpec
import chrepl.config.validate.SecretReader
import chrepl.masking.MaskingEngine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Builds clients for configured hosts, resolving credentials from secret references. */
class ClientFactory(
    private val secrets: SecretReader = SecretReader.SYSTEM,
) {
    fun create(
        host: HostPort,
        tls: Boolean,
        user: String,
        password: SecretRef,
    ): ClickHouseClient {
        val secret = secrets.read(password) ?: throw TargetSafetyException("cannot read the password for $user@${host.host} ($password)")
        return ClickHouseClient(Endpoint(host.host, host.port ?: if (tls) 8443 else 8123, tls), user, secret)
    }
}

data class PartitionResult(
    val targetHost: String,
    val partitionId: String,
    val rowsCopied: Long,
    val rowsInPartition: Long,
)

data class TableResult(
    val table: String,
    val target: String,
    val chunks: Int,
    val rowsCopied: Long,
    val partitions: List<PartitionResult>,
    val duration: Duration,
)

/** Schema of one table on the source and on every target host; enough to run chunks and assemblies. */
data class TableContext(
    val spec: TableSpec,
    val source: TableInfo,
    val targets: List<TableInfo>,
) {
    val database: String get() = source.database
    val table: String get() = source.name
}

/** One target partition to rebuild: its chunks (possibly none, which empties it) on one target host. */
data class PartitionWork(
    val targetIndex: Int,
    val partitionId: String,
    val chunks: List<Chunk>,
)

/** A table ready to copy: context, selection, the metadata plan and the per-target-partition work. */
data class PreparedTable(
    val context: TableContext,
    val selection: Selection,
    val plan: TablePlan,
    val work: List<PartitionWork>,
)

/**
 * The steps of copying tables from the configured source to one target (SPEC §5), usable
 * sequentially ([CopyJob]) or distributed across workers (control plane, PLAN M4).
 */
class CopySession(
    private val config: ReplicatorConfig,
    val targetName: String,
    private val engine: MaskingEngine,
    private val gate: ReadGate,
    private val clients: ClientFactory = ClientFactory(),
    private val locks: PartitionLocks = PartitionLocks.IN_PROCESS,
) {
    private val sourceName = config.sources.keys.single()
    private val source = config.sources.getValue(sourceName)
    private val target = config.targets[targetName] ?: throw IllegalArgumentException("unknown target '$targetName'")

    init {
        require(gate !== UnguardedGate || source.dc == "test") {
            "an unguarded copy is only allowed for dc: test sources; '$sourceName' is dc: ${source.dc}"
        }
        if (target.shardMapping == ShardMapping.DISTRIBUTED) {
            throw UnsupportedOperationException("shard_mapping: distributed is not implemented yet; use same_shards or single")
        }
    }

    /** Reader clients of every DR replica, per shard name, keyed by `host:port`. */
    val sourceReplicas: Map<String, Map<String, ClickHouseClient>> by lazy {
        source.shards.associate { shard ->
            shard.name to
                shard.hosts.associate { host ->
                    clients.create(host, source.tls, source.reader.user, source.reader.password).let { it.endpoint.toString() to it }
                }
        }
    }

    /** One reader client per shard for metadata (schema, planning): the replica the gate prefers when the session starts. */
    val sourceClients: Map<String, ClickHouseClient> by lazy {
        sourceReplicas.mapValues { (_, replicas) -> replicas.getValue(gate.preferred(replicas.keys.toList()).first()) }
    }

    /** Target shards in order, each with its replicas (`hosts` is shard-major). */
    val targetShards: List<TargetShard> by lazy {
        target.hosts
            .chunked(target.replicas)
            .take(target.shards)
            .mapIndexed {
                i,
                hosts,
                ->
                TargetShard(i, hosts.map { clients.create(it, target.tls, target.writer.user, target.writer.password) })
            }
    }

    /** The writer of each target shard: its first replica that is up (SPEC §5.2). */
    val targetClients: List<ClickHouseClient> get() = targetShards.map { it.writer() }

    /** Every target replica (cleanup of staging tables, which live on whichever replica was the writer). */
    val allTargetClients: List<ClickHouseClient> get() = targetShards.flatMap { it.replicas }

    /** Lock key prefix for one target shard: stable whichever replica writes. */
    fun lockScope(targetIndex: Int): String = "$targetName/shard${targetIndex + 1}"

    /** Path for `repl_meta` tables on a replicated target, or null (plain MergeTree) for single-replica shards. */
    private val metaPath: ((String) -> String)? =
        target.zookeeperPathTemplate?.takeIf { target.replicas > 1 }?.let { template ->
            { table -> template.replace("{database}", "repl_meta").replace("{table}", table) }
        }

    fun spec(tableName: String): TableSpec =
        config.tables.firstOrNull { it.source == tableName } ?: throw IllegalArgumentException("no table spec for '$tableName'")

    fun selection(
        spec: TableSpec,
        cobDates: List<LocalDate>,
        partitions: List<String>? = null,
    ): Selection =
        when {
            partitions != null -> Selection.Partitions(partitions.toSet())
            spec.fullCopy -> Selection.All
            cobDates.isEmpty() -> throw IllegalArgumentException("${spec.source} is COB-sliced: give at least one COB date")
            else -> Selection.Cob(cobDates.distinct().sorted())
        }

    /** Reads the table's schema on the source (checking every shard agrees) and on the targets; writes nothing. */
    fun context(tableName: String): TableContext {
        val spec = spec(tableName)
        val (database, table) = tableName.split('.', limit = 2)
        val infos =
            sourceClients.mapValues { (shard, client) ->
                SchemaInspector.read(client, database, table) ?: throw SchemaException("$tableName does not exist on source shard $shard")
            }
        val sourceInfo = infos.values.first()
        SchemaInspector.requireCopyable(sourceInfo)
        infos.forEach { (shard, info) ->
            if (info.columns !=
                sourceInfo.columns
            ) {
                throw SchemaException("$tableName: schema on shard $shard differs from shard ${infos.keys.first()}")
            }
        }
        val targets =
            targetClients.map { client ->
                SchemaInspector.read(client, database, table)
                    ?: throw SchemaException("$tableName does not exist on target ${client.endpoint}")
            }
        return TableContext(spec, sourceInfo, targets)
    }

    /**
     * Steps 1–3 of a table copy: source schema (the masker compiles against it, so unclassified columns
     * fail here), target safety on every host before any write, target schema, and the metadata plan.
     */
    fun prepare(
        tableName: String,
        cobDates: List<LocalDate>,
        partitions: List<String>? = null,
        /** A rekey job may overwrite a table masked with another key version (SPEC §6.2). */
        allowRekey: Boolean = false,
    ): PreparedTable {
        val spec = spec(tableName)
        val selection = selection(spec, cobDates, partitions)
        val (database, table) = tableName.split('.', limit = 2)
        val infos =
            sourceClients.mapValues { (shard, client) ->
                SchemaInspector.read(client, database, table) ?: throw SchemaException("$tableName does not exist on source shard $shard")
            }
        val sourceInfo = infos.values.first()
        SchemaInspector.requireCopyable(sourceInfo)
        infos.forEach { (shard, info) ->
            if (info.columns !=
                sourceInfo.columns
            ) {
                throw SchemaException("$tableName: schema on shard $shard differs from shard ${infos.keys.first()}")
            }
        }
        engine.compile(spec, sourceInfo.columns) // throws MaskingConfigException listing every problem

        // Every reachable replica must carry the marker; the writer must be among them.
        val replicas = targetShards.map { it.writer() to it.reachable() }
        replicas.forEach { (_, reachable) -> reachable.forEach { TargetMeta.checkMarker(it, targetName) } }
        replicas.forEach { (writer, _) -> TargetMeta.checkKeyVersion(writer, database, table, engine.realm, engine.keyVersion, allowRekey) }
        val targetInfos =
            replicas.map { (writer, reachable) ->
                TargetMeta.ensureTables(writer, metaPath)
                val info = SchemaSync.ensure(writer, sourceInfo, target)
                // Other replicas get the writer's table (CREATE is not replicated; ALTERs and data are).
                for (replica in reachable.filter { it !== writer }) {
                    TargetMeta.ensureTables(replica, metaPath)
                    SchemaSync.ensureReplica(replica, info)
                }
                info
            }
        if (spec.mvMode == MvMode.BASE_ONLY) {
            val sourceClient = sourceClients.values.first()
            val views = MaterializedViews.dependents(sourceClient, database, table)
            if (views.isNotEmpty()) {
                replicas.forEach { (writer, reachable) ->
                    MaterializedViews.ensureOnTarget(sourceClient, writer, target, views)
                    reachable.filter { it !== writer }.forEach {
                        MaterializedViews.ensureOnTarget(
                            sourceClient,
                            it,
                            target,
                            views,
                            primary = writer,
                        )
                    }
                }
            }
        }

        val plan = ChunkPlanner(spec.chunk?.maxBytes?.bytes ?: DEFAULT_CHUNK_BYTES).plan(spec, sourceInfo, selection, sourceClients)
        spec.maxFullCopyBytes?.let { cap ->
            if (plan.bytes > cap.bytes) throw SchemaException("$tableName is ${plan.bytes} bytes, above max_full_copy_bytes ${cap.bytes}")
        }
        val shardIndex = source.shards.map { it.name }
        val work =
            targetClients.indices.flatMap { targetIndex ->
                val shardPlans =
                    when (target.shardMapping) {
                        ShardMapping.SAME_SHARDS -> plan.shards.filter { shardIndex.indexOf(it.shard) == targetIndex }
                        else -> plan.shards
                    }
                val sourcePartitions = shardPlans.flatMap { s -> s.partitions.map { it.partitionId } }.toSet()
                // Target partitions holding the requested slice (or, for full copies, any partition) must be rebuilt
                // even when the source has nothing for them, so stale rows disappear.
                val stale =
                    ChunkPlanner(Long.MAX_VALUE)
                        .plan(
                            spec,
                            targetInfos[targetIndex],
                            selection,
                            mapOf(
                                "target" to targetClients[targetIndex],
                            ),
                        ).partitionIds
                (sourcePartitions + stale).sorted().map { partitionId ->
                    PartitionWork(
                        targetIndex,
                        partitionId,
                        shardPlans.flatMap { s ->
                            s.partitions.filter { it.partitionId == partitionId }.flatMap { it.chunks }
                        },
                    )
                }
            }
        return PreparedTable(TableContext(spec, sourceInfo, targetInfos), selection, plan, work)
    }

    /** Copies one chunk into its own staging table on the target host; throws [PreemptedException] if the guard stops it. */
    fun copyChunk(
        context: TableContext,
        targetIndex: Int,
        chunk: Chunk,
        staging: String,
        queryId: String,
    ): Long {
        val masker = engine.compile(context.spec, context.source.columns)
        return ChunkCopier(context.spec, context.source, gate).copy(
            chunk,
            sourceReplicas.getValue(chunk.shard),
            targetClients[targetIndex],
            context.targets[targetIndex],
            staging,
            masker,
            queryId,
        )
    }

    /** Rebuilds one target partition from chunk stagings and publishes it with REPLACE PARTITION. */
    fun assemble(
        context: TableContext,
        targetIndex: Int,
        partitionId: String,
        selection: Selection,
        chunkStagings: List<String>,
        staging: String,
        record: CopyRecord,
    ): Long {
        val client = targetClients[targetIndex]
        val total =
            PartitionAssembler(
                context.spec,
                locks,
            ).assemble(client, lockScope(targetIndex), context.targets[targetIndex], partitionId, selection, chunkStagings, staging)
        TargetMeta.recordCopy(client, record.copy(sourceDelaySeconds = record.sourceDelaySeconds ?: sourceDelay(context)))
        return total
    }

    fun finishTable(context: TableContext) =
        targetClients.forEachIndexed { i, it ->
            TargetMeta.recordKeyVersion(it, context.database, context.table, engine.realm, engine.keyVersion)
            // Views do not fire on partition replacement: rebuild their targets from the copied base table.
            if (context.spec.mvMode == MvMode.BASE_ONLY) {
                locks.withLock("${lockScope(i)}/${context.database}.${context.table}/views") {
                    MaterializedViews.recompute(it, context.database, context.table)
                }
            }
        }

    val realm: String get() = engine.realm
    val keyVersion: Int get() = engine.keyVersion

    fun targetHost(targetIndex: Int): String = targetClients[targetIndex].endpoint.toString()

    /** Largest replication delay of the source table on DR (null when not replicated). */
    private fun sourceDelay(context: TableContext): Long? =
        sourceClients.values
            .mapNotNull {
                it
                    .queryScalar(
                        "SELECT max(absolute_delay) FROM system.replicas WHERE database = {db:String} AND table = {t:String} HAVING count() > 0",
                        mapOf("param_db" to context.database, "param_t" to context.table),
                    )?.toLongOrNull()
            }.maxOrNull()

    companion object {
        val DEFAULT_CHUNK_BYTES: Long = 2L shl 30

        /** Staging tables are named so that the janitor can recognise orphans (`__stg_<8 hex>_`). */
        fun stagingName(
            table: String,
            jobId: String,
            suffix: String,
        ) = "${table}__stg_${jobId.takeLast(8)}_$suffix"
    }
}

/**
 * Copies tables to one target sequentially in this process (SPEC §5): the foreground path used by
 * the CLI. The control plane runs the same [CopySession] steps distributed across workers.
 */
class CopyJob(
    config: ReplicatorConfig,
    targetName: String,
    engine: MaskingEngine,
    gate: ReadGate,
    clients: ClientFactory = ClientFactory(),
    locks: PartitionLocks = PartitionLocks.IN_PROCESS,
    val jobId: String = newJobId(),
    private val requester: String = "cli",
    private val configVersion: String = "local",
    /** Pause before re-reading a pre-empted chunk; the guard also blocks until the source is GREEN again. */
    private val retryBackoff: (attempt: Int) -> Duration = { Duration.ofSeconds(minOf(1L shl minOf(it - 1, 5), 30L)) },
    private val listener: JobListener = JobListener.NONE,
) {
    private val session = CopySession(config, targetName, engine, gate, clients, locks)

    fun copy(
        tableName: String,
        cobDates: List<LocalDate> = emptyList(),
    ): TableResult {
        val started = Instant.now()
        val prepared = session.prepare(tableName, cobDates)
        val context = prepared.context
        val results = mutableListOf<PartitionResult>()
        var chunkSeq = 0
        for (work in prepared.work) {
            val stagings = mutableListOf<String>()
            var rows = 0L
            for (chunk in work.chunks) {
                val staging = CopySession.stagingName(context.table, jobId, "c${chunkSeq++}")
                rows +=
                    copyWithRetry(
                        chunk,
                    ) { attempt ->
                        session.copyChunk(
                            context,
                            work.targetIndex,
                            chunk,
                            staging,
                            SourceQueries.queryId(jobId, chunk.id, attempt),
                        )
                    }
                stagings += staging
            }
            val record =
                CopyRecord(
                    jobId,
                    requester,
                    context.database,
                    context.table,
                    work.partitionId,
                    (prepared.selection as? Selection.Cob)?.dates ?: emptyList(),
                    rows,
                    session.realm,
                    session.keyVersion,
                    configVersion,
                    null,
                )
            val total =
                session.assemble(
                    context,
                    work.targetIndex,
                    work.partitionId,
                    prepared.selection,
                    stagings,
                    CopySession.stagingName(context.table, jobId, "p${chunkSeq++}"),
                    record,
                )
            results += PartitionResult(session.targetHost(work.targetIndex), work.partitionId, rows, total)
        }
        session.finishTable(context)
        return TableResult(
            tableName,
            session.targetName,
            prepared.plan.chunks.size,
            results.sumOf {
                it.rowsCopied
            },
            results,
            Duration.between(started, Instant.now()),
        )
    }

    /** Pre-emptions are not failures: the chunk is re-read (into a fresh staging table) until it completes. */
    private fun copyWithRetry(
        chunk: Chunk,
        copy: (attempt: Int) -> Long,
    ): Long {
        var attempt = 1
        while (true) {
            try {
                return copy(attempt).also { listener.chunkDone(chunk, it) }
            } catch (e: PreemptedException) {
                listener.chunkPreempted(chunk, attempt, e.message ?: "")
                Thread.sleep(retryBackoff(attempt).toMillis())
                attempt++
            }
        }
    }

    companion object {
        fun newJobId(): String =
            "j" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(16)
    }
}

/** Progress callbacks (the control plane records them; M4). */
interface JobListener {
    fun chunkDone(
        chunk: Chunk,
        rows: Long,
    ) {}

    fun chunkPreempted(
        chunk: Chunk,
        attempt: Int,
        reason: String,
    ) {}

    companion object {
        val NONE = object : JobListener {}
    }
}

/** Drops staging tables left behind by crashed jobs (SPEC §5.4). */
object Janitor {
    fun dropOrphanStaging(
        client: ClickHouseClient,
        olderThan: Duration,
    ): List<String> {
        val rows =
            client.queryJson(
                "SELECT database, name FROM system.tables WHERE match(name, '__stg_[0-9a-f]{8}_') " +
                    "AND metadata_modification_time < now() - toIntervalSecond({age:UInt64})",
                mapOf("param_age" to olderThan.seconds.toString()),
            )
        return rows.map { row ->
            val name = "${row["database"].textValue()}.${row["name"].textValue()}"
            client.execute("DROP TABLE IF EXISTS ${Sql.table(name)}")
            name
        }
    }
}
