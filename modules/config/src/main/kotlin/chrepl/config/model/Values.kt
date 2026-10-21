package chrepl.config.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Duration

/** A duration written as `<number><unit>`, unit one of `ms`, `s`, `m`, `h`, `d` (e.g. `5s`, `10m`, `36h`). */
class ConfigDuration private constructor(
    val value: Duration,
    private val text: String,
) : Comparable<ConfigDuration> {
    override fun compareTo(other: ConfigDuration): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is ConfigDuration && other.value == value

    override fun hashCode(): Int = value.hashCode()

    @JsonValue
    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex("""^(\d+)(ms|s|m|h|d)$""")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): ConfigDuration {
            val match =
                PATTERN.matchEntire(text.trim())
                    ?: throw IllegalArgumentException("invalid duration '$text' (expected e.g. 500ms, 5s, 10m, 1h, 2d)")
            val amount = match.groupValues[1].toLong()
            val duration =
                when (match.groupValues[2]) {
                    "ms" -> Duration.ofMillis(amount)
                    "s" -> Duration.ofSeconds(amount)
                    "m" -> Duration.ofMinutes(amount)
                    "h" -> Duration.ofHours(amount)
                    else -> Duration.ofDays(amount)
                }
            return ConfigDuration(duration, text.trim())
        }
    }
}

/** A byte size written as `<number><unit>`, binary (`KiB`…`TiB`) or decimal (`KB`…`TB`) units, or plain `B`. */
class DataSize private constructor(
    val bytes: Long,
    private val text: String,
) : Comparable<DataSize> {
    override fun compareTo(other: DataSize): Int = bytes.compareTo(other.bytes)

    override fun equals(other: Any?): Boolean = other is DataSize && other.bytes == bytes

    override fun hashCode(): Int = bytes.hashCode()

    @JsonValue
    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex("""^(\d+)\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)$""")
        private val MULTIPLIERS =
            mapOf(
                "B" to 1L,
                "KiB" to (1L shl 10),
                "MiB" to (1L shl 20),
                "GiB" to (1L shl 30),
                "TiB" to (1L shl 40),
                "KB" to 1_000L,
                "MB" to 1_000_000L,
                "GB" to 1_000_000_000L,
                "TB" to 1_000_000_000_000L,
            )

        fun ofBytes(bytes: Long): DataSize = DataSize(bytes, "${bytes}B")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): DataSize {
            val match =
                PATTERN.matchEntire(text.trim())
                    ?: throw IllegalArgumentException("invalid size '$text' (expected e.g. 512MiB, 2GiB, 50GB)")
            val amount = match.groupValues[1].toLong()
            val multiplier = MULTIPLIERS.getValue(match.groupValues[2])
            if (amount > Long.MAX_VALUE / multiplier) throw IllegalArgumentException("size '$text' is too large")
            return DataSize(amount * multiplier, text.trim())
        }
    }
}

/**
 * A reference to a secret. Only references are accepted in secret fields; the validator reports
 * literals and unsupported schemes (SPEC §9.1), so parsing itself never fails.
 */
class SecretRef private constructor(
    val kind: Kind,
    /** File path, env var name, the scheme (for [Kind.UNSUPPORTED]), or empty for [Kind.LITERAL]. */
    val target: String,
) {
    enum class Kind { FILE, ENV, UNSUPPORTED, LITERAL }

    val isReference: Boolean get() = kind == Kind.FILE || kind == Kind.ENV

    override fun equals(other: Any?): Boolean = other is SecretRef && other.kind == kind && other.target == target

    override fun hashCode(): Int = 31 * kind.hashCode() + target.hashCode()

    /** Never prints a literal value. */
    @JsonValue
    override fun toString(): String =
        when (kind) {
            Kind.FILE -> "\${file:$target}"
            Kind.ENV -> "\${env:$target}"
            Kind.UNSUPPORTED -> "\${$target:…}"
            Kind.LITERAL -> "<literal>"
        }

    companion object {
        private val PATTERN = Regex("""^\$\{([a-z][a-z0-9_-]*):(.+)}$""")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): SecretRef {
            val match = PATTERN.matchEntire(text.trim()) ?: return SecretRef(Kind.LITERAL, "")
            val (scheme, value) = match.destructured
            return when (scheme) {
                "file" -> SecretRef(Kind.FILE, value)
                "env" -> SecretRef(Kind.ENV, value)
                else -> SecretRef(Kind.UNSUPPORTED, scheme)
            }
        }
    }
}

/** `host`, `host:port` or `[ipv6]:port`. */
class HostPort private constructor(
    val host: String,
    val port: Int?,
    private val text: String,
) {
    /** Lower-cased host without a trailing dot, for comparisons. */
    val normalizedHost: String get() = host.lowercase().removeSuffix(".")

    override fun equals(other: Any?): Boolean = other is HostPort && other.normalizedHost == normalizedHost && other.port == port

    override fun hashCode(): Int = 31 * normalizedHost.hashCode() + (port ?: 0)

    @JsonValue
    override fun toString(): String = text

    companion object {
        private val BRACKETED = Regex("""^\[([0-9A-Fa-f:.]+)](?::(\d+))?$""")
        private val PLAIN = Regex("""^([A-Za-z0-9._-]+)(?::(\d+))?$""")

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: String): HostPort {
            val trimmed = text.trim()
            val match =
                BRACKETED.matchEntire(trimmed)
                    ?: PLAIN.matchEntire(trimmed)
                    ?: throw IllegalArgumentException("invalid host '$text' (expected host, host:port or [ipv6]:port)")
            val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (port != null && port !in 1..65535) throw IllegalArgumentException("invalid port in '$text'")
            return HostPort(match.groupValues[1], port, trimmed)
        }
    }
}
