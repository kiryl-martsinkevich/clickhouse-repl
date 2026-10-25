package chrepl.control

import chrepl.clickhouse.Sql
import chrepl.transfer.TargetMeta
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

/**
 * Retention on shared targets (SPEC §11): drops COB partitions whose newest COB date is older than
 * the target's `retention`. A partition is dropped only when every row in it is past retention.
 * Writes nothing unless the target's environment marker matches; holds the same partition lock as
 * assembly, so it never races a copy of that partition.
 */
class RetentionJanitor(
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One pass over every target with a retention; returns the dropped `target/table/partition`s. */
    fun run(): List<String> {
        val version = configs.currentVersion
        val config = configs.get(version)
        val dropped = mutableListOf<String>()
        for ((name, target) in config.targets) {
            val retention = target.retention?.value ?: continue
            val cutoff = LocalDate.now(clock).minusDays(retention.toDays())
            val session = runtime.session(version, name) { _, _ -> }
            for (spec in config.tables.filter { it.cobColumn != null && !it.fullCopy }) {
                val (database, table) = spec.source.split('.', limit = 2)
                for (client in session.targetClients) {
                    if (client.queryScalar("EXISTS TABLE ${Sql.ident(database)}.${Sql.ident(table)}") != "1") continue
                    TargetMeta.checkMarker(client, name)
                    val expired = SourceMetadata.partitions(client, database, table).filter { it.maxDate != null && it.maxDate < cutoff }
                    for (partition in expired) {
                        runtime.locks.withLock("${client.endpoint}/$database.$table/${partition.id}") {
                            client.execute("ALTER TABLE ${Sql.table(spec.source)} DROP PARTITION ID ${Sql.string(partition.id)}")
                        }
                        dropped += "$name/${spec.source}/${partition.id}"
                        log.info(
                            "retention: dropped {} partition {} on {} (newest COB {} < {})",
                            spec.source,
                            partition.id,
                            name,
                            partition.maxDate,
                            cutoff,
                        )
                    }
                }
            }
        }
        if (dropped.isNotEmpty()) {
            db.tx { audit("janitor", "retention.drop", null, mapOf("partitions" to dropped)) }
            ControlMetrics.retentionDrops(dropped.size)
        }
        return dropped
    }

    companion object {
        private val log = LoggerFactory.getLogger(RetentionJanitor::class.java)
    }
}
