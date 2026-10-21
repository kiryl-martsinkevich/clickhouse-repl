package chrepl.config.validate

import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

/** Resolves host names; replaceable in tests. */
fun interface HostResolver {
    /** All addresses of [host]; empty when it does not resolve. */
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM = HostResolver { host -> runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList()) }
    }
}

/** Reads secret values; replaceable in tests. */
fun interface SecretReader {
    /** The secret's value, or null when it cannot be read. */
    fun read(ref: SecretRef): String?

    companion object {
        val SYSTEM =
            SecretReader { ref ->
                when (ref.kind) {
                    SecretRef.Kind.ENV -> System.getenv(ref.target)
                    SecretRef.Kind.FILE -> runCatching { Files.readString(Path.of(ref.target)).trim() }.getOrNull()
                    else -> null
                }
            }
    }
}

/**
 * Which checks need the outside world. Defaults are offline, suitable for CI; the application
 * enables all three when it loads a config (SPEC §9.3).
 */
data class ValidationOptions(
    /** Resolve every source/target host and check the addresses against the active-DC CIDRs. */
    val resolveDns: Boolean = false,
    /** Read secrets and check the masking key length. */
    val resolveSecrets: Boolean = false,
    /** Check that referenced files (dictionaries) exist. */
    val checkFiles: Boolean = false,
    val hostResolver: HostResolver = HostResolver.SYSTEM,
    val secretReader: SecretReader = SecretReader.SYSTEM,
)

/** Semantic validation of a bound config (SPEC §9.3). Fails closed: anything unsafe is an error. */
class ConfigValidator(
    private val options: ValidationOptions = ValidationOptions(),
) {
    fun validate(
        config: ReplicatorConfig,
        baseDir: Path? = null,
    ): ValidationReport {
        val issues = IssueCollector()
        val context = RuleContext(config, options, baseDir, issues)
        context.checkIsolation()
        context.checkSources()
        context.checkTargets()
        context.checkMasking()
        context.checkTables()
        context.checkDatasets()
        context.checkContinuous()
        context.checkGuard()
        context.checkAccess()
        return issues.report()
    }
}

internal class RuleContext(
    val config: ReplicatorConfig,
    val options: ValidationOptions,
    val baseDir: Path?,
    issues: IssueCollector,
) {
    private val collector = issues

    val tableNames: Set<String> by lazy { config.tables.map { it.source }.toSet() }
    val sourceShardCount: Int? by lazy {
        config.sources.values
            .singleOrNull()
            ?.shards
            ?.size
    }

    fun error(
        code: Code,
        path: String,
        message: String,
    ) = collector.error(code, path, message)

    fun warn(
        code: Code,
        path: String,
        message: String,
    ) = collector.warn(code, path, message)

    /** Reports literals and unsupported schemes in a secret field; optionally checks it can be read. */
    fun checkSecret(
        ref: SecretRef,
        path: String,
    ): String? {
        when (ref.kind) {
            SecretRef.Kind.LITERAL -> {
                error(Code.SECRET_LITERAL, path, "secrets must be references (\${file:/path} or \${env:NAME}), not literal values")
                return null
            }

            SecretRef.Kind.UNSUPPORTED -> {
                val hint = if (ref.target == "fortanix") " (Fortanix DSM support is planned for M8)" else ""
                error(Code.SECRET_SCHEME, path, "unsupported secret scheme '${ref.target}'$hint; use file or env")
                return null
            }

            else -> {}
        }
        if (!options.resolveSecrets) return null
        val value = options.secretReader.read(ref)
        if (value.isNullOrEmpty()) error(Code.SECRET_UNRESOLVED, path, "cannot read secret $ref")
        return value
    }
}

internal fun <T> List<T>.duplicates(key: (T) -> String): Set<String> = groupingBy(key).eachCount().filterValues { it > 1 }.keys

internal val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
