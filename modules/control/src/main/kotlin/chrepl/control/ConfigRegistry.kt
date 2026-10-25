package chrepl.control

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ReplicatorConfig
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.ValidationOptions
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Versioned configuration (SPEC §9.1). Every activated version is stored in Postgres; jobs record
 * the version they were created with and keep running with it after a reload.
 */
class ConfigRegistry(
    private val db: Db,
    private val path: Path,
    private val options: ValidationOptions,
    private val refresh: Duration = Duration.ofSeconds(5),
) {
    private val cache = ConcurrentHashMap<String, ReplicatorConfig>()

    @Volatile private var active: String = ""

    @Volatile private var checkedAt = 0L

    /**
     * The active version: the most recently activated one in Postgres, so a reload through any API
     * replica applies to every process within [refresh].
     */
    val currentVersion: String
        get() {
            val now = System.nanoTime()
            if (active.isEmpty() || now - checkedAt > refresh.toNanos()) {
                db.tx { one("SELECT version FROM config_versions ORDER BY activated_at DESC LIMIT 1") { it.getString(1) } }?.let {
                    active =
                        it
                }
                checkedAt = now
            }
            return active.ifEmpty { throw IllegalStateException("no configuration has been loaded") }
        }

    val baseDir: Path get() = if (path.toFile().isDirectory) path else path.toAbsolutePath().parent

    val current: ReplicatorConfig get() = get(currentVersion)

    /** Loads and validates the config at [path]; activates it only if valid. Returns the version. */
    fun reload(actor: String): String {
        val tree = ConfigLoader.readTree(path)
        val loaded = ConfigLoader.bind(tree)
        val config =
            loaded.config
                ?: throw RequestException("configuration does not load: ${loaded.issues.joinToString { "${it.code} ${it.path}" }}")
        val report = ConfigValidator(options).validate(config, baseDir)
        if (!report.isValid) {
            throw RequestException(
                "configuration is invalid: ${report.errors.joinToString { "${it.code} ${it.path}: ${it.message}" }}",
            )
        }
        val content = ConfigLoader.mapper.writeValueAsString(tree)
        val version = sha256(content)
        db.tx {
            update(
                "INSERT INTO config_versions (version, content, loaded_by) VALUES (?, ?, ?) " +
                    "ON CONFLICT (version) DO UPDATE SET activated_at = clock_timestamp()",
                version,
                content,
                actor,
            )
            update(
                "INSERT INTO audit_log (actor, action, details) VALUES (?, 'config.reload', ?::jsonb)",
                actor,
                Db.json.writeValueAsString(
                    mapOf(
                        "version" to version,
                    ),
                ),
            )
        }
        cache[version] = config
        active = version
        checkedAt = System.nanoTime()
        return version
    }

    fun get(version: String): ReplicatorConfig =
        cache.getOrPut(version) {
            val content =
                db.tx { one("SELECT content FROM config_versions WHERE version = ?", version) { it.getString(1) } }
                    ?: throw NotFoundException("unknown config version $version")
            ConfigLoader.bind(ConfigLoader.mapper.readTree(content) as ObjectNode).config ?: error("stored config $version no longer binds")
        }

    private fun sha256(text: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)
}
