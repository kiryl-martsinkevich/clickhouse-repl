package chrepl.masking.bench

import chrepl.config.load.ConfigLoader
import chrepl.config.model.Domain
import chrepl.config.model.ReplicatorConfig
import chrepl.config.validate.SecretReader
import chrepl.masking.MaskingEngine
import chrepl.masking.MaskingSettings
import chrepl.masking.keys.MasterKey
import chrepl.masking.keys.StaticKeyProvider
import chrepl.masking.table.ColumnSchema
import chrepl.masking.table.TableMasker
import chrepl.types.ChType
import chrepl.types.TypeParser
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val ROWS = 10_000

private fun exampleConfig(): Pair<ReplicatorConfig, Path> {
    val path = Path.of(System.getProperty("chrepl.exampleConfig"))
    return ConfigLoader.load(path).config!! to path.parent
}

private val TEST_KEY = HexFormat.of().formatHex(ByteArray(32) { it.toByte() })

/** Uncompressed RowBinary size of a value, the unit the throughput target is expressed in. */
internal fun rowBinarySize(
    value: Any?,
    type: ChType,
): Int =
    when (type) {
        is ChType.NullableType -> 1 + if (value == null) 0 else rowBinarySize(value, type.inner)
        is ChType.LowCardinalityType -> rowBinarySize(value, type.inner)
        is ChType.IntType -> type.bits / 8
        is ChType.FloatType -> type.bits / 8
        is ChType.DecimalType ->
            if (type.precision <= 9) {
                4
            } else if (type.precision <= 18) {
                8
            } else if (type.precision <= 38) {
                16
            } else {
                32
            }
        ChType.DateType -> 2
        ChType.Date32Type -> 4
        is ChType.DateTimeType -> 4
        is ChType.DateTime64Type -> 8
        is ChType.FixedStringType -> type.length
        is ChType.ArrayType -> 1 + (value as List<*>).sumOf { rowBinarySize(it, type.element) }
        is ChType.MapType ->
            1 +
                (value as Map<*, *>).entries.sumOf { rowBinarySize(it.key, type.key) + rowBinarySize(it.value, type.value) }
        else -> (value as String).toByteArray().size + 1
    }

/**
 * Masks batches of rows shaped like `trading.trades` from the example config. `realistic` repeats
 * customers, accounts and counterparties as a fact table would (the LRU cache helps); `distinct`
 * makes every masked value unique and disables the cache (worst case).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class TradesBenchmark {
    @Param("realistic", "distinct")
    var data: String = "realistic"

    private lateinit var masker: TableMasker
    private lateinit var rows: List<Array<Any?>>

    private val schema =
        listOf(
            "trade_id" to "UInt64",
            "trade_date" to "Date",
            "inserted_at" to "DateTime64(3)",
            "instrument_id" to "UInt32",
            "customer_id" to "UInt64",
            "account_number" to "String",
            "counterparty_name" to "LowCardinality(String)",
            "quantity" to "Int64",
            "price" to "Decimal(18, 6)",
            "notional" to "Decimal(18, 2)",
            "trader_comment" to "Nullable(String)",
            "tags" to "Array(LowCardinality(String))",
            "attributes" to "Map(String, String)",
        ).map { ColumnSchema(it.first, TypeParser.parse(it.second)) }

    @Setup(Level.Trial)
    fun setup() {
        val (config, baseDir) = exampleConfig()
        val distinct = data == "distinct"
        val settings = if (distinct) MaskingSettings(cacheSize = 0) else MaskingSettings()
        val engine = MaskingEngine.create(config.masking, StaticKeyProvider(config.masking, SecretReader { TEST_KEY }), baseDir, settings)
        masker = engine.compile(config.tables.first { it.source == "trading.trades" }, schema)
        val random = Random(42)
        val counterparties = listOf("Northwind Capital", "Bluefield Partners", "Cobalt Capital", "Meridian Group", "Acme Holdings")
        rows =
            (0 until ROWS).map { i ->
                val customer = if (distinct) 100_000L + i else 100_000L + random.nextInt(2_000)
                arrayOf(
                    BigInteger.valueOf(i.toLong()),
                    LocalDate.of(2026, 10, 19),
                    Instant.parse("2026-10-19T10:00:00Z").plusMillis(i.toLong()),
                    random.nextLong(1, 50_000),
                    BigInteger.valueOf(customer),
                    "GB29NWBK6016%08d".format(customer % 100_000_000),
                    if (distinct) "Counterparty $i" else counterparties[random.nextInt(counterparties.size)],
                    random.nextLong(1, 1_000_000),
                    BigDecimal("101.250000"),
                    BigDecimal(random.nextInt(1_000_000)).movePointLeft(2),
                    if (random.nextInt(4) == 0) "call client re settlement $i" else null,
                    listOf("fx", "spot"),
                    linkedMapOf(
                        "client_email" to "client$customer@bank.com",
                        "client_phone" to "+44 20 7946 %04d".format(customer % 10_000),
                        "desk" to "rates",
                    ),
                )
            }
        val bytes = rows.sumOf { row -> row.indices.sumOf { rowBinarySize(row[it], schema[it].type) } }
        println("\n[trades/$data] average uncompressed RowBinary row size: ${bytes / ROWS} bytes")
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    fun maskRows(bh: Blackhole) {
        for (row in rows) bh.consume(masker.mask(row))
    }
}

/** Raw per-value cost of each strategy, cache disabled, every input distinct. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class StrategyBenchmark {
    @Param("tokenize_string", "hash", "fpe_ff1_digits", "email", "dictionary", "date_shift", "numeric_noise")
    var strategy: String = "tokenize_string"

    private lateinit var mask: (Any?) -> Any?
    private lateinit var inputs: List<Any>

    @Setup(Level.Trial)
    fun setup() {
        val (config, baseDir) = exampleConfig()
        val domains = config.masking.domains
        val (domain, type, gen) =
            when (strategy) {
                "tokenize_string" -> Triple(domains.getValue("account_number"), "String") { i: Int -> "GB29NWBK6016%08d".format(i) }
                "hash" -> Triple(Domain(chrepl.config.model.Strategy.HASH, length = 16), "String") { i: Int -> "user-$i" }
                "fpe_ff1_digits" -> Triple(domains.getValue("customer_id"), "UInt64") { i: Int -> BigInteger.valueOf(100_000L + i) }
                "email" -> Triple(domains.getValue("email"), "String") { i: Int -> "first.last$i@bank.com" }
                "dictionary" -> Triple(domains.getValue("first_name"), "String") { i: Int -> "Name$i" }
                "date_shift" ->
                    Triple(domains.getValue("customer_dates"), "Date") { i: Int ->
                        LocalDate.of(1970, 1, 1).plusDays(
                            i.toLong() % 40_000,
                        )
                    }
                else -> Triple(domains.getValue("amount_noise"), "Decimal(18, 2)") { i: Int -> BigDecimal(i).movePointLeft(2) }
            }
        val masking = config.masking.copy(domains = mapOf("d" to domain))
        val dictionaries = chrepl.masking.Dictionaries.load(config.masking, baseDir)
        val engine =
            MaskingEngine(
                masking,
                MasterKey(
                    masking.realm,
                    masking.key.version,
                    ByteArray(32) {
                        it.toByte()
                    },
                ),
                dictionaries,
                MaskingSettings(cacheSize = 0),
            )
        val spec =
            config.tables.first().copy(
                source = "bench.t",
                cobColumn = null,
                fullCopy = true,
                columns =
                    mapOf(
                        "c" to
                            chrepl.config.model.ColumnPolicy
                                .Mask("d"),
                    ),
            )
        val masker = engine.compile(spec, listOf(ColumnSchema("c", TypeParser.parse(type))))
        mask = { v -> masker.mask(arrayOf(v))[0] }
        inputs = (0 until ROWS).map(gen)
    }

    @Benchmark
    @OperationsPerInvocation(ROWS)
    fun maskValues(bh: Blackhole) {
        for (v in inputs) bh.consume(mask(v))
    }
}
