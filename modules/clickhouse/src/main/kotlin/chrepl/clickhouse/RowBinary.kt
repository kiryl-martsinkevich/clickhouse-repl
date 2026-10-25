package chrepl.clickhouse

import chrepl.types.ChType
import chrepl.types.TypeParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Data that does not follow the RowBinary format or the expected types. */
class RowBinaryException(
    message: String,
) : Exception(message)

private const val BUFFER = 1 shl 16
private val TWO_64: BigInteger = BigInteger.ONE.shiftLeft(64)

/** Decimal storage width in bytes for a precision (Decimal32/64/128/256). */
internal fun decimalWidth(precision: Int): Int =
    when {
        precision <= 9 -> 4
        precision <= 18 -> 8
        precision <= 38 -> 16
        else -> 32
    }

/**
 * Reads ClickHouse `RowBinary` / `RowBinaryWithNamesAndTypes` into the JVM value model documented
 * on [ChType]. Strings that are not valid UTF-8 are returned as [ByteArray] so they survive a
 * pass-through copy byte for byte. JSON columns must be sent as strings
 * (`output_format_binary_write_json_as_string = 1`).
 */
class RowBinaryReader(
    input: InputStream,
) {
    private val input = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER)
    private val scratch = ByteArray(32)
    private val utf8 =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(
                CodingErrorAction.REPORT,
            ).onUnmappableCharacter(CodingErrorAction.REPORT)

    /** Header of `RowBinaryWithNamesAndTypes`: column names and parsed types. */
    fun readHeader(): List<Pair<String, ChType>> {
        val count = readVarInt().toInt()
        val names = List(count) { readUtf8() }
        val types = List(count) { readUtf8() }
        return names.zip(types.map { TypeParser.parse(it) })
    }

    /** True when at least one more byte (i.e. another row) is available. */
    fun hasMore(): Boolean {
        input.mark(1)
        val b = input.read()
        input.reset()
        return b >= 0
    }

    fun readRow(types: List<ChType>): Array<Any?> = Array(types.size) { readValue(types[it]) }

    fun readValue(type: ChType): Any? =
        when (type) {
            is ChType.NullableType -> if (readByte() != 0) null else readValue(type.inner)
            is ChType.LowCardinalityType -> readValue(type.inner)
            is ChType.IntType -> readInt(type)
            is ChType.FloatType ->
                if (type.bits ==
                    32
                ) {
                    java.lang.Float
                        .intBitsToFloat(readLE(4).toInt())
                        .toDouble()
                } else {
                    java.lang.Double.longBitsToDouble(readLE(8))
                }
            is ChType.DecimalType -> BigDecimal(readSigned(decimalWidth(type.precision)), type.scale)
            ChType.BoolType -> readByte() != 0
            ChType.StringType, ChType.JsonType -> readString(readVarInt().toInt())
            is ChType.FixedStringType -> readString(type.length)
            ChType.DateType -> LocalDate.ofEpochDay(readLE(2) and 0xFFFF)
            ChType.Date32Type -> LocalDate.ofEpochDay(readLE(4).toInt().toLong())
            is ChType.DateTimeType -> Instant.ofEpochSecond(readLE(4) and 0xFFFF_FFFFL)
            is ChType.DateTime64Type -> {
                val ticks = readLE(8)
                val unit = POW10[type.precision]
                Instant.ofEpochSecond(Math.floorDiv(ticks, unit), Math.floorMod(ticks, unit) * POW10[9 - type.precision])
            }
            ChType.UuidType -> {
                val high = readLE(8)
                val low = readLE(8)
                UUID(high, low)
            }
            ChType.Ipv4Type -> {
                val v = readLE(4)
                InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
            }
            ChType.Ipv6Type -> InetAddress.getByAddress(readBytes(16))
            is ChType.EnumType -> {
                val code = if (type.bits == 8) readByte().toByte().toInt() else readLE(2).toShort().toInt()
                type.values.entries
                    .firstOrNull { it.value == code }
                    ?.key ?: throw RowBinaryException("unknown value $code for $type")
            }
            is ChType.ArrayType -> List(readVarInt().toInt()) { readValue(type.element) }
            is ChType.MapType -> {
                val n = readVarInt().toInt()
                val map = LinkedHashMap<Any?, Any?>(n * 2)
                repeat(n) { map[readValue(type.key)] = readValue(type.value) }
                map
            }
            is ChType.TupleType -> type.elements.map { readValue(it) }
            is ChType.Unsupported -> throw RowBinaryException("cannot read values of unsupported type $type")
        }

    private fun readInt(type: ChType.IntType): Any {
        val bytes = type.bits / 8
        if (bytes <= 8) {
            val raw = readLE(bytes)
            return when {
                bytes == 8 && type.signed -> raw
                bytes == 8 -> if (raw >= 0) BigInteger.valueOf(raw) else BigInteger.valueOf(raw).add(TWO_64)
                type.signed -> (raw shl (64 - 8 * bytes)) shr (64 - 8 * bytes)
                else -> raw
            }
        }
        return if (type.signed) readSigned(bytes) else readUnsigned(bytes)
    }

    private fun readSigned(bytes: Int): BigInteger = BigInteger(readBytes(bytes).reversedArray())

    private fun readUnsigned(bytes: Int): BigInteger = BigInteger(1, readBytes(bytes).reversedArray())

    private fun readString(length: Int): Any {
        val bytes = readBytes(length)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            bytes
        }
    }

    private fun readUtf8(): String = String(readBytes(readVarInt().toInt()), Charsets.UTF_8)

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("unexpected end of RowBinary data")
        return b
    }

    /** Little-endian unsigned integer of up to 8 bytes, as raw long bits. */
    private fun readLE(bytes: Int): Long {
        readFully(scratch, bytes)
        var v = 0L
        for (i in bytes - 1 downTo 0) v = (v shl 8) or (scratch[i].toLong() and 0xFF)
        return v
    }

    private fun readBytes(n: Int): ByteArray {
        if (n < 0) throw RowBinaryException("negative length $n")
        val out = ByteArray(n)
        readFully(out, n)
        return out
    }

    private fun readFully(
        buffer: ByteArray,
        n: Int,
    ) {
        var offset = 0
        while (offset < n) {
            val r = input.read(buffer, offset, n - offset)
            if (r < 0) throw EOFException("unexpected end of RowBinary data")
            offset += r
        }
    }

    private fun readVarInt(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw RowBinaryException("varint too long")
        }
    }
}

/** Writes values of the [ChType] model as `RowBinary`. Call [flush] (or [close]) at the end. */
class RowBinaryWriter(
    output: OutputStream,
) : AutoCloseable {
    private val out = if (output is BufferedOutputStream) output else BufferedOutputStream(output, BUFFER)
    var rows: Long = 0
        private set

    fun writeRow(
        values: Array<Any?>,
        types: List<ChType>,
    ) {
        if (values.size != types.size) throw RowBinaryException("row has ${values.size} values, expected ${types.size}")
        for (i in values.indices) writeValue(values[i], types[i])
        rows++
    }

    fun writeValue(
        value: Any?,
        type: ChType,
    ) {
        when (type) {
            is ChType.NullableType -> {
                if (value == null) {
                    out.write(1)
                } else {
                    out.write(0)
                    writeValue(value, type.inner)
                }
            }
            is ChType.LowCardinalityType -> writeValue(value, type.inner)
            else -> {
                if (value == null) throw RowBinaryException("NULL for non-nullable $type")
                writeNonNull(value, type)
            }
        }
    }

    private fun writeNonNull(
        value: Any,
        type: ChType,
    ) {
        when (type) {
            is ChType.IntType -> writeInt(value, type)
            is ChType.FloatType ->
                if (type.bits == 32) {
                    writeLE(
                        java.lang.Float
                            .floatToRawIntBits((value as Number).toFloat())
                            .toLong(),
                        4,
                    )
                } else {
                    writeLE(java.lang.Double.doubleToRawLongBits((value as Number).toDouble()), 8)
                }
            is ChType.DecimalType -> {
                val unscaled =
                    try {
                        (value as BigDecimal).setScale(type.scale, java.math.RoundingMode.UNNECESSARY).unscaledValue()
                    } catch (e: ArithmeticException) {
                        throw RowBinaryException("decimal does not fit scale ${type.scale}")
                    }
                writeTwosComplement(unscaled, decimalWidth(type.precision))
            }
            ChType.BoolType -> out.write(if (value as Boolean) 1 else 0)
            ChType.StringType, ChType.JsonType -> {
                val bytes = stringBytes(value)
                writeVarInt(bytes.size.toLong())
                out.write(bytes)
            }
            is ChType.FixedStringType -> {
                val bytes = stringBytes(value)
                if (bytes.size > type.length) throw RowBinaryException("value longer than ${type.length} bytes for $type")
                out.write(bytes)
                repeat(type.length - bytes.size) { out.write(0) }
            }
            ChType.DateType -> {
                val days = (value as LocalDate).toEpochDay()
                if (days !in 0..65535) throw RowBinaryException("date outside the Date range")
                writeLE(days, 2)
            }
            ChType.Date32Type -> writeLE((value as LocalDate).toEpochDay(), 4)
            is ChType.DateTimeType -> {
                val seconds = (value as Instant).epochSecond
                if (seconds !in 0..0xFFFF_FFFFL) throw RowBinaryException("time outside the DateTime range")
                writeLE(seconds, 4)
            }
            is ChType.DateTime64Type -> {
                val instant = value as Instant
                val ticks =
                    Math.addExact(
                        Math.multiplyExact(instant.epochSecond, POW10[type.precision]),
                        instant.nano / POW10[9 - type.precision],
                    )
                writeLE(ticks, 8)
            }
            ChType.UuidType -> {
                val uuid = value as UUID
                writeLE(uuid.mostSignificantBits, 8)
                writeLE(uuid.leastSignificantBits, 8)
            }
            ChType.Ipv4Type -> {
                val b = (value as? Inet4Address ?: throw RowBinaryException("IPv4 column needs an IPv4 address")).address
                out.write(b[3].toInt())
                out.write(b[2].toInt())
                out.write(b[1].toInt())
                out.write(b[0].toInt())
            }
            ChType.Ipv6Type -> {
                when (value) {
                    is Inet6Address -> out.write(value.address)
                    is Inet4Address -> {
                        out.write(ByteArray(10))
                        out.write(0xFF)
                        out.write(0xFF)
                        out.write(value.address)
                    }
                    else -> throw RowBinaryException("IPv6 column needs an IP address")
                }
            }
            is ChType.EnumType -> {
                val code = type.values[value as String] ?: throw RowBinaryException("'$value' is not a value of the enum")
                writeLE(code.toLong(), type.bits / 8)
            }
            is ChType.ArrayType -> {
                val list = value as List<*>
                writeVarInt(list.size.toLong())
                for (e in list) writeValue(e, type.element)
            }
            is ChType.MapType -> {
                val map = value as Map<*, *>
                writeVarInt(map.size.toLong())
                for ((k, v) in map) {
                    writeValue(k, type.key)
                    writeValue(v, type.value)
                }
            }
            is ChType.TupleType -> {
                val list = value as List<*>
                if (list.size !=
                    type.elements.size
                ) {
                    throw RowBinaryException("tuple has ${list.size} elements, expected ${type.elements.size}")
                }
                list.forEachIndexed { i, e -> writeValue(e, type.elements[i]) }
            }
            is ChType.Unsupported -> throw RowBinaryException("cannot write values of unsupported type $type")
            is ChType.NullableType, is ChType.LowCardinalityType -> writeValue(value, type)
        }
    }

    private fun writeInt(
        value: Any,
        type: ChType.IntType,
    ) {
        val bytes = type.bits / 8
        val big =
            when (value) {
                is BigInteger -> value
                is Number -> BigInteger.valueOf(value.toLong())
                else -> throw RowBinaryException("integer column needs a number")
            }
        if (big < type.min || big > type.max) throw RowBinaryException("integer outside the range of $type")
        if (bytes <= 8) writeLE(big.toLong(), bytes) else writeTwosComplement(big, bytes)
    }

    private fun writeTwosComplement(
        value: BigInteger,
        bytes: Int,
    ) {
        val be = value.toByteArray()
        if (be.size > bytes + 1 ||
            (be.size == bytes + 1 && be[0] != 0.toByte())
        ) {
            throw RowBinaryException("value does not fit $bytes bytes")
        }
        val pad: Byte = if (value.signum() < 0) -1 else 0
        for (i in 0 until bytes) {
            val src = be.size - 1 - i
            out.write((if (src >= 0) be[src] else pad).toInt())
        }
    }

    private fun stringBytes(value: Any): ByteArray =
        when (value) {
            is String -> value.toByteArray(Charsets.UTF_8)
            is ByteArray -> value
            else -> throw RowBinaryException("string column needs a String")
        }

    private fun writeLE(
        value: Long,
        bytes: Int,
    ) {
        for (i in 0 until bytes) out.write((value ushr (8 * i)).toInt() and 0xFF)
    }

    private fun writeVarInt(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun flush() = out.flush()

    override fun close() = out.close()
}

private val POW10 =
    LongArray(19).also { a ->
        a[0] = 1
        for (i in 1 until a.size) a[i] = a[i - 1] * 10
    }
