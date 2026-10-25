package chrepl.config.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** An IPv4 or IPv6 network in CIDR notation. */
class Cidr private constructor(
    private val network: ByteArray,
    val prefixLength: Int,
    private val text: String,
) {
    operator fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) if (bytes[i] != network[i]) return false
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    override fun toString(): String = text

    companion object {
        /** Parses `a.b.c.d/n` or `x:y::z/n`; a bare address is a host route (/32 or /128). */
        fun parse(text: String): Cidr {
            val trimmed = text.trim()
            val addressPart = trimmed.substringBefore('/')
            val address =
                IpLiterals.parse(addressPart)
                    ?: throw IllegalArgumentException("'$text' is not an IPv4/IPv6 network")
            val maxPrefix = address.address.size * 8
            val prefix =
                if ('/' in trimmed) {
                    trimmed.substringAfter('/').toIntOrNull()?.takeIf { it in 0..maxPrefix }
                        ?: throw IllegalArgumentException("'$text' has an invalid prefix length")
                } else {
                    maxPrefix
                }
            return Cidr(address.address, prefix, trimmed)
        }
    }
}

/** Parses IP literals without ever performing a DNS lookup. */
object IpLiterals {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val IPV6 = Regex("""^[0-9A-Fa-f:.]+$""")

    fun parse(text: String): InetAddress? {
        IPV4.matchEntire(text)?.let { match ->
            val octets = match.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return null
            return InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
        }
        if (':' in text && IPV6.matches(text)) {
            // A literal containing ':' is never resolved via DNS by getByName.
            return runCatching { InetAddress.getByName(text) }.getOrNull()?.takeIf { it is Inet6Address || it is Inet4Address }
        }
        return null
    }
}
