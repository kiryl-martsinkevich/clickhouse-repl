package chrepl.masking

import chrepl.config.model.Masking
import chrepl.config.model.TableSpec
import chrepl.masking.keys.MasterKey
import chrepl.masking.keys.MasterKeyProvider
import chrepl.masking.strategy.DomainKeys
import chrepl.masking.strategy.Strategies
import chrepl.masking.table.ColumnSchema
import chrepl.masking.table.DomainMasker
import chrepl.masking.table.TableCompiler
import chrepl.masking.table.TableMasker
import java.nio.file.Files
import java.nio.file.Path

data class MaskingSettings(
    /** Entries per column in the masked-value LRU cache; 0 disables caching. */
    val cacheSize: Int = 65_536,
    /** Distinct outputs per domain and chunk tracked by `collision_check` before it stops. */
    val collisionLimit: Int = 5_000_000,
)

/**
 * Entry point of the masking engine (SPEC §6). Holds the master key and per-domain derived keys
 * (thread-safe, read-only) and compiles [TableMasker]s, which are per-thread. [close] wipes keys.
 */
class MaskingEngine(
    private val masking: Masking,
    private val key: MasterKey,
    private val dictionaries: Map<String, List<String>>,
    private val settings: MaskingSettings = MaskingSettings(),
) : AutoCloseable {
    init {
        require(key.realm == masking.realm) { "master key realm ${key.realm} does not match config realm ${masking.realm}" }
        require(
            key.version == masking.key.version,
        ) { "master key version ${key.version} does not match config version ${masking.key.version}" }
    }

    private val domainKeys: Map<String, DomainKeys> =
        masking.domains.keys.associateWith { DomainKeys(key.derive("hmac", it), key.derive("ff1", it)) }

    @Volatile
    private var closed = false

    val realm: String get() = masking.realm
    val keyVersion: Int get() = key.version

    /** Compiles [spec] against the table's real [schema]; throws [MaskingConfigException] listing every problem. */
    fun compile(
        spec: TableSpec,
        schema: List<ColumnSchema>,
    ): TableMasker {
        check(!closed) { "masking engine is closed" }
        val cache = mutableMapOf<String, DomainMasker>()
        return TableCompiler(
            spec,
            schema,
            domainMasker = { name ->
                cache.getOrPut(name) {
                    val domain = masking.domains[name] ?: return@TableCompiler null
                    DomainMasker(name, domain, Strategies.create(domain, domainKeys.getValue(name), dictionaries), settings.collisionLimit)
                }
            },
            cacheSize = settings.cacheSize,
        ).compile()
    }

    override fun close() {
        closed = true
        domainKeys.values.forEach { it.wipe() }
        key.close()
    }

    override fun toString() = "MaskingEngine(realm=${masking.realm}, keyVersion=${key.version}, domains=${masking.domains.size})"

    companion object {
        fun create(
            masking: Masking,
            keyProvider: MasterKeyProvider,
            baseDir: Path?,
            settings: MaskingSettings = MaskingSettings(),
        ): MaskingEngine = MaskingEngine(masking, keyProvider.load(), Dictionaries.load(masking, baseDir), settings)
    }
}

/** Loads dictionary files: one entry per line; blank lines and `#` comments are skipped. */
object Dictionaries {
    fun load(
        masking: Masking,
        baseDir: Path?,
    ): Map<String, List<String>> {
        val problems = mutableListOf<String>()
        val loaded =
            masking.dictionaries.mapValues { (name, dictionary) ->
                val file = baseDir?.resolve(dictionary.file) ?: Path.of(dictionary.file)
                val entries =
                    runCatching { Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } }
                        .getOrElse {
                            problems += "dictionary '$name': cannot read $file"
                            emptyList()
                        }
                if (entries.isEmpty() && problems.none { it.startsWith("dictionary '$name'") }) problems += "dictionary '$name' is empty"
                entries.distinct()
            }
        if (problems.isNotEmpty()) throw MaskingConfigException(problems)
        return loaded
    }
}
