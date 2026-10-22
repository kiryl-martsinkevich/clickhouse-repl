package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Endpoint
import chrepl.clickhouse.Sql
import chrepl.config.model.HostPort
import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import chrepl.config.model.ShardMapping
import chrepl.config.model.TableSpec
import chrepl.config.validate.SecretReader
import chrepl.masking.MaskingEngine
import chrepl.masking.table.TableMasker
import java.time.Duration
import java.time.Instant
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

/**
 * Copies tables from the configured source to one target (SPEC §5): schema sync, planning, chunk
 * copies through the [ReadGate], partition assembly and bookkeeping in `repl_meta`. Sequential;
 * the control plane (PLAN M4) parallelises chunks across workers.
 */
class CopyJob(
    private val config: ReplicatorConfig,
    private val targetName: String,
    private val engine: MaskingEngine,
    private val gate: ReadGate,
    private val clients: ClientFactory = ClientFactory(),
    private val locks: PartitionLocks = PartitionLocks.IN_PROCESS,
    val jobId: String = newJobId(),
    private val requester: String = "cli",
    private val configVersion: String = "local",
    /** Pause before re-reading a pre-empted chunk; the guard also blocks until the source is GREEN again. */
    private val retryBackoff: (attempt: Int) -> Duration = { Duration.ofSeconds(minOf(1L shl minOf(it - 1, 5), 30L)) },
    private val listener: JobListener = JobListener.NONE,
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

    private val sourceClients: Map<String, ClickHouseClient> by lazy {
        source.shards.associate { it.name to clients.create(it.host, source.tls, source.reader.user, source.reader.password) }
    }
    private val targetClients: List<ClickHouseClient> by lazy {
        target.hosts.take(target.shards).map { clients.create(it, target.tls, target.writer.user, target.writer.password) }
    }

    fun copy(
        tableName: String,
        cobDates: List<java.time.LocalDate> = emptyList(),
    ): TableResult {
        val started = Instant.now()
        val spec = config.tables.firstOrNull { it.source == tableName } ?: throw IllegalArgumentException("no table spec for '$tableName'")
        val selection =
            when {
                spec.fullCopy -> Selection.All
                cobDates.isEmpty() -> throw IllegalArgumentException("$tableName is COB-sliced: give at least one COB date")
                else -> Selection.Cob(cobDates.distinct().sorted())
            }
        val (database, table) = tableName.split('.', limit = 2)

        // 1. Source schema, identical on every shard; masker compiled against it (fails on unclassified columns).
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
        val masker: TableMasker = engine.compile(spec, sourceInfo.columns)

        // 2. Target safety on every host before anything is written, then bookkeeping tables and schema.
        targetClients.forEach { TargetMeta.checkMarker(it, targetName) }
        targetClients.forEach { TargetMeta.checkKeyVersion(it, database, table, engine.realm, engine.keyVersion) }
        val targetInfos =
            targetClients.map { client ->
                TargetMeta.ensureTables(client)
                SchemaSync.ensure(client, sourceInfo, target)
            }

        // 3. Plan from metadata.
        val maxChunk = spec.chunk?.maxBytes?.bytes ?: DEFAULT_CHUNK_BYTES
        val plan = ChunkPlanner(maxChunk).plan(spec, sourceInfo, selection, sourceClients)
        spec.maxFullCopyBytes?.let { cap ->
            if (plan.bytes > cap.bytes) throw SchemaException("$tableName is ${plan.bytes} bytes, above max_full_copy_bytes ${cap.bytes}")
        }

        // 4. Copy chunks into staging, then assemble each target partition.
        val copier = ChunkCopier(spec, sourceInfo, gate)
        val assembler = PartitionAssembler(spec, locks)
        val results = mutableListOf<PartitionResult>()
        var rowsCopied = 0L
        val shardIndex = source.shards.map { it.name }
        for ((targetIndex, targetClient) in targetClients.withIndex()) {
            val targetInfo = targetInfos[targetIndex]
            val shardPlans =
                when (target.shardMapping) {
                    ShardMapping.SAME_SHARDS -> plan.shards.filter { shardIndex.indexOf(it.shard) == targetIndex }
                    else -> plan.shards
                }
            val sourcePartitions = shardPlans.flatMap { s -> s.partitions.map { it.partitionId } }.toSet()
            // Target partitions holding the requested slice but absent at the source must be emptied too.
            val partitions = (sourcePartitions + stalePartitions(targetClient, spec, targetInfo, selection)).sorted()
            for (partitionId in partitions) {
                val chunkStagings = mutableListOf<String>()
                var partitionRows = 0L
                val chunks = shardPlans.flatMap { s -> s.partitions.filter { it.partitionId == partitionId }.flatMap { it.chunks } }
                for (chunk in chunks) {
                    val staging = stagingName(table, chunkStagings.size.toString() + "_" + targetIndex)
                    val sourceClient = sourceClients.getValue(chunk.shard)
                    partitionRows +=
                        copyWithRetry(chunk) { attempt ->
                            copier.copy(
                                chunk,
                                sourceClient,
                                targetClient,
                                targetInfo,
                                staging,
                                masker,
                                SourceQueries.queryId(jobId, chunk.id, attempt),
                            )
                        }
                    chunkStagings += staging
                }
                val total =
                    assembler.assemble(
                        targetClient,
                        targetInfo,
                        partitionId,
                        selection,
                        chunkStagings,
                        stagingName(table, "p$targetIndex"),
                    )
                TargetMeta.recordCopy(
                    targetClient,
                    CopyRecord(
                        jobId,
                        requester,
                        database,
                        table,
                        partitionId,
                        (selection as? Selection.Cob)?.dates ?: emptyList(),
                        partitionRows,
                        engine.realm,
                        engine.keyVersion,
                        configVersion,
                        sourceDelay(shardPlans, database, table),
                    ),
                )
                results += PartitionResult(targetClient.endpoint.toString(), partitionId, partitionRows, total)
                rowsCopied += partitionRows
            }
            TargetMeta.recordKeyVersion(targetClient, database, table, engine.realm, engine.keyVersion)
        }
        return TableResult(tableName, targetName, plan.chunks.size, rowsCopied, results, Duration.between(started, Instant.now()))
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

    /** For COB slices: target partitions containing the dates; for full copies: target partitions missing at the source. */
    private fun stalePartitions(
        client: ClickHouseClient,
        spec: TableSpec,
        info: TableInfo,
        selection: Selection,
    ): Set<String> {
        val plan = ChunkPlanner(Long.MAX_VALUE).plan(spec, info, selection, mapOf("target" to client))
        return plan.partitionIds
    }

    /** Largest replication delay of the source table on the shards read (null when not replicated). */
    private fun sourceDelay(
        shards: List<ShardPlan>,
        database: String,
        table: String,
    ): Long? =
        shards
            .mapNotNull { s ->
                sourceClients
                    .getValue(s.shard)
                    .queryScalar(
                        "SELECT max(absolute_delay) FROM system.replicas WHERE database = {db:String} AND table = {t:String} HAVING count() > 0",
                        mapOf("param_db" to database, "param_t" to table),
                    )?.toLongOrNull()
            }.maxOrNull()

    private fun stagingName(
        table: String,
        suffix: String,
    ) = "${table}__stg_${jobId.takeLast(8)}_$suffix"

    companion object {
        val DEFAULT_CHUNK_BYTES: Long = 2L shl 30

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
