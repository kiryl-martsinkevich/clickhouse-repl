package chrepl.masking.strategy

import chrepl.config.model.Alphabets
import chrepl.masking.MaskingException
import chrepl.masking.box
import chrepl.masking.toBigIntegerValue
import chrepl.types.ChType
import org.bouncycastle.crypto.fpe.FPEFF1Engine
import org.bouncycastle.crypto.params.FPEParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.ln

/**
 * NIST SP 800-38G FF1 format-preserving encryption (SPEC §6.3): a bijection, so masked keys stay
 * unique. Two modes:
 *
 * - **digits** (`alphabet: digits`, `width: W`): the value is a non-negative integer below 10^W,
 *   from an integer column or a digit-only string. It is encrypted as a W-digit block and the
 *   result is an integer below 10^W. Integer and string columns therefore mask consistently;
 *   string outputs carry no leading zeros. The column type must hold 10^W − 1.
 * - **other alphabets**: the string itself is encrypted over the alphabet, keeping its length.
 *   Every character must be in the alphabet and the string must be long enough for the NIST
 *   minimum domain size (radix^length ≥ 10^6).
 */
class Ff1Strategy(
    key: ByteArray,
    alphabetName: String,
    private val width: Int?,
) : ScalarStrategy {
    private val alphabet: String = Alphabets.BY_NAME[alphabetName] ?: throw MaskingException("unknown FF1 alphabet '$alphabetName'")
    private val radix = alphabet.length
    private val digits = alphabetName == Alphabets.DIGITS
    private val minLength = ceil(ln(1_000_000.0) / ln(radix.toDouble()) - 1e-9).toInt()
    private val limit: BigInteger? = width?.let { BigInteger.TEN.pow(it) }
    private val encryptor = engine(key, true)
    private val decryptor = engine(key, false)

    init {
        if (digits) {
            require(
                width != null && width in Alphabets.DIGIT_WIDTHS,
            ) { "fpe_ff1 over digits needs width in ${Alphabets.DIGIT_WIDTHS}" }
        }
    }

    override fun unsupported(type: ChType): String? =
        when {
            type == ChType.StringType -> null
            type is ChType.IntType && !digits -> "fpe_ff1 on integer columns needs alphabet: digits"
            type is ChType.IntType && limit!! - BigInteger.ONE > type.max -> "fpe_ff1 width $width does not fit $type (max ${type.max})"
            type is ChType.IntType -> null
            else -> "fpe_ff1 supports String and integer columns, not $type"
        }

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any = transform(value, type, encryptor)

    /** The inverse permutation; used by tests to demonstrate bijectivity. */
    fun unmask(
        value: Any,
        type: ChType,
    ): Any = transform(value, type, decryptor)

    private fun transform(
        value: Any,
        type: ChType,
        engine: FPEFF1Engine,
    ): Any {
        if (!digits) return encryptString(value as String, engine)
        val number =
            when (value) {
                is String -> {
                    if (value.isEmpty() ||
                        !value.all { it in '0'..'9' }
                    ) {
                        throw MaskingException("fpe_ff1 digits domain needs digit-only strings")
                    }
                    BigInteger(value)
                }

                else -> {
                    value.toBigIntegerValue()
                }
            }
        if (number.signum() < 0) throw MaskingException("fpe_ff1 digits domain accepts non-negative integers only")
        if (number >= limit!!) throw MaskingException("fpe_ff1 value does not fit width $width")
        val block =
            number
                .toString()
                .padStart(width!!, '0')
                .map { (it - '0').toByte() }
                .toByteArray()
        val out = ByteArray(block.size)
        engine.processBlock(block, 0, block.size, out, 0)
        val result = BigInteger(out.joinToString("") { it.toString() })
        return if (type is ChType.IntType) type.box(result) else result.toString()
    }

    private fun encryptString(
        text: String,
        engine: FPEFF1Engine,
    ): String {
        if (text.length < minLength) throw MaskingException("fpe_ff1 value is shorter than the NIST minimum of $minLength characters")
        val block =
            ByteArray(text.length) { i ->
                val index = alphabet.indexOf(text[i])
                if (index < 0) throw MaskingException("fpe_ff1 value contains characters outside its alphabet")
                index.toByte()
            }
        val out = ByteArray(block.size)
        engine.processBlock(block, 0, block.size, out, 0)
        return String(CharArray(out.size) { alphabet[out[it].toInt() and 0xFF] })
    }

    /** Called during construction, after [radix] is initialised. The tweak is empty: the key is already per domain. */
    private fun engine(
        key: ByteArray,
        encrypt: Boolean,
    ): FPEFF1Engine = FPEFF1Engine().apply { init(encrypt, FPEParameters(KeyParameter(key), radix, ByteArray(0))) }
}
