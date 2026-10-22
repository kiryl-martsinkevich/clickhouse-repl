package chrepl.control

import chrepl.clickhouse.Cancellation
import chrepl.config.model.Guard
import chrepl.config.validate.SecretReader
import chrepl.guard.GuardGate
import chrepl.guard.GuardLevel
import chrepl.guard.OpsFlags
import chrepl.guard.Reason
import chrepl.guard.SourceGuard
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.StaticKeyProvider
import chrepl.transfer.ClientFactory
import chrepl.transfer.CopySession
import chrepl.transfer.PartitionLocks
import chrepl.transfer.ReadGate
import chrepl.transfer.UnguardedGate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Operator flags in Postgres, shared by every process (SPEC §4.6, §4.7). */
class PgOpsFlags(
    private val db: Db,
) : OpsFlags {
    override val paused: Boolean
        get() = db.tx { long("SELECT count(*) FROM ops_flags WHERE name = 'paused'") > 0 }

    override val failover: Reason?
        get() =
            db.tx {
                one("SELECT value::text FROM ops_flags WHERE name = 'failover'") { Db.json.readValue(it.getString(1), Reason::class.java) }
            }

    override fun pause(reason: String) = set("paused", mapOf("reason" to reason), "operator")

    override fun resume() = clear("paused", "operator")

    /** Only the first trigger is kept, so the original cause stays visible. */
    override fun setFailover(reason: Reason) {
        db.tx {
            update(
                "INSERT INTO ops_flags (name, value, set_by) VALUES ('failover', ?::jsonb, ?) ON CONFLICT (name) DO NOTHING",
                Db.json.writeValueAsString(reason),
                if (reason.code == "DR_ACTIVE" && reason.host != null) "guard" else "operator",
            )
        }
    }

    override fun clearFailover() = clear("failover", "operator")

    fun set(
        name: String,
        value: Any,
        actor: String,
    ) {
        db.tx {
            update(
                "INSERT INTO ops_flags (name, value, set_by) VALUES (?, ?::jsonb, ?) " +
                    "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, set_by = EXCLUDED.set_by, set_at = now()",
                name,
                Db.json.writeValueAsString(value),
                actor,
            )
            update(
                "INSERT INTO audit_log (actor, action, details) VALUES (?, ?, ?::jsonb)",
                actor,
                "flag.set.$name",
                Db.json.writeValueAsString(value),
            )
        }
    }

    fun clear(
        name: String,
        actor: String,
    ) {
        db.tx {
            update("DELETE FROM ops_flags WHERE name = ?", name)
            update("INSERT INTO audit_log (actor, action) VALUES (?, ?)", actor, "flag.clear.$name")
        }
    }
}

/**
 * Cluster-wide read admission (SPEC §4.4): at most `concurrency.global` reads in total and
 * `concurrency.per_host` per DR host, across every worker process. Tokens are leased so a crashed
 * worker's tokens expire.
 */
class DbReadTokens(
    private val db: Db,
    private val guard: () -> Guard,
    private val holder: String,
    private val lease: Duration = Duration.ofMinutes(2),
) {
    fun acquire(
        host: String,
        queryId: String,
        onWait: (String) -> Unit,
    ) {
        var reported = false
        while (true) {
            val limits = guard().concurrency
            val granted =
                db.tx {
                    exec("SELECT pg_advisory_xact_lock(7319)")
                    update("DELETE FROM read_tokens WHERE lease_until < now()")
                    val total = long("SELECT count(*) FROM read_tokens")
                    val onHost = long("SELECT count(*) FROM read_tokens WHERE host = ?", host)
                    if (total < limits.global && onHost < limits.perHost) {
                        update(
                            "INSERT INTO read_tokens (query_id, host, holder, lease_until) VALUES (?, ?, ?, now() + make_interval(secs => ?))",
                            queryId,
                            host,
                            holder,
                            lease.seconds.toDouble(),
                        )
                        true
                    } else {
                        false
                    }
                }
            if (granted) return
            if (!reported) {
                onWait("waiting for a read slot (limits: ${limits.global} total, ${limits.perHost} per DR host)")
                reported = true
            }
            Thread.sleep(500)
        }
    }

    fun renew(queryId: String) {
        db.tx {
            update(
                "UPDATE read_tokens SET lease_until = now() + make_interval(secs => ?) WHERE query_id = ?",
                lease.seconds.toDouble(),
                queryId,
            )
        }
    }

    fun release(queryId: String) {
        db.tx { update("DELETE FROM read_tokens WHERE query_id = ?", queryId) }
    }
}

/** REPLACE PARTITION serialised per (target host, table, partition) with Postgres advisory locks (SPEC §5.4). */
class PgPartitionLocks(
    private val db: Db,
) : PartitionLocks {
    override fun <T> withLock(
        key: String,
        block: () -> T,
    ): T =
        db.dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))").use {
                it.setString(1, key)
                it.execute()
            }
            try {
                block()
            } finally {
                conn.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use {
                    it.setString(1, key)
                    it.execute()
                }
            }
        }
}

/**
 * Per-process runtime shared by workers: masking engines and copy sessions per config version and
 * target, and the read gate (DB tokens → Source Guard → read). Running reads can be cancelled by
 * query id, which is how job cancellation reaches an in-flight chunk.
 */
class Runtime(
    private val configs: ConfigRegistry,
    private val db: Db,
    private val secrets: SecretReader,
    val guard: SourceGuard?,
    private val workerId: String,
    private val guardWait: Duration = Duration.ofDays(1),
    /** Runs after admission, just before a read starts (diagnostics and tests). */
    private val beforeRead: (queryId: String) -> Unit = {},
) : AutoCloseable {
    val clients = ClientFactory(secrets)
    private val engines = ConcurrentHashMap<String, MaskingEngine>()
    private val running = ConcurrentHashMap<String, Cancellation>()
    private val tokens = DbReadTokens(db, { configs.current.guard }, workerId)
    private val locks = PgPartitionLocks(db)

    fun engine(version: String): MaskingEngine =
        engines.computeIfAbsent(version) {
            val config = configs.get(version)
            MaskingEngine.create(config.masking, StaticKeyProvider(config.masking, secrets), configs.baseDir)
        }

    fun session(
        version: String,
        target: String,
        onWait: (queryId: String, reason: String) -> Unit,
    ): CopySession {
        // The gate is created per call so wait reasons reach the right chunk; sessions are cheap.
        val config = configs.get(version)
        val source = config.sources.values.single()
        return CopySession(config, target, engine(version), gate(source.dc == "test", onWait), clients, locks)
    }

    private fun gate(
        unguarded: Boolean,
        onWait: (String, String) -> Unit,
    ): ReadGate {
        val inner: ReadGate =
            if (unguarded) {
                UnguardedGate
            } else {
                GuardGate(
                    guard ?: error("no Source Guard in this process"),
                    guardWait,
                ) { q, reasons -> onWait(q, reasons.joinToString("; ")) }
            }
        return object : ReadGate {
            override fun <T> withPermit(
                host: String,
                queryId: String,
                read: (Cancellation) -> T,
            ): T {
                tokens.acquire(host, queryId) { onWait(queryId, it) }
                try {
                    return inner.withPermit(host, queryId) { cancellation ->
                        running[queryId] = cancellation
                        try {
                            beforeRead(queryId)
                            read(cancellation)
                        } finally {
                            running.remove(queryId)
                        }
                    }
                } finally {
                    tokens.release(queryId)
                }
            }
        }
    }

    /** DR metadata queries (planning, estimates) run only while the guard is GREEN or AMBER (SPEC §4.4). */
    fun metadataAllowed(): Boolean = guard?.status()?.level?.let { it == GuardLevel.GREEN || it == GuardLevel.AMBER } ?: true

    /** Why DR metadata may not be read now, or null when it may. */
    fun metadataBlockedReason(): String? =
        if (metadataAllowed()) {
            null
        } else {
            guard?.status()?.let { status -> status.reasons.joinToString("; ").ifEmpty { "source is ${status.level}" } }
        }

    /** Cancels an in-flight read (job cancellation); returns true when it was running here. */
    fun cancel(queryId: String): Boolean =
        running[queryId]?.let {
            it.cancel()
            true
        } ?: false

    fun renewToken(queryId: String) = tokens.renew(queryId)

    override fun close() {
        engines.values.forEach { it.close() }
        guard?.close()
    }
}
