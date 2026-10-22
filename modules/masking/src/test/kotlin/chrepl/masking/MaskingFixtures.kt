package chrepl.masking

import chrepl.config.model.ColumnPolicy
import chrepl.config.model.DataSize
import chrepl.config.model.Domain
import chrepl.config.model.Masking
import chrepl.config.model.MaskingKey
import chrepl.config.model.SecretRef
import chrepl.config.model.TableSpec
import chrepl.masking.keys.MasterKey
import chrepl.masking.table.ColumnSchema
import chrepl.masking.table.TableMasker
import chrepl.types.TypeParser

/** Builds engines and table maskers for tests from a fixed, test-only key. */
object MaskingFixtures {
    val KEY_A: ByteArray = ByteArray(32) { it.toByte() }
    val KEY_B: ByteArray = ByteArray(32) { (255 - it).toByte() }

    fun masking(
        domains: Map<String, Domain>,
        realm: String = "nonprod",
        version: Int = 1,
    ) = Masking(realm, MaskingKey("static", SecretRef.parse("\${env:TEST_KEY}"), version), emptyMap(), domains)

    fun engine(
        domains: Map<String, Domain>,
        dictionaries: Map<String, List<String>> = emptyMap(),
        realm: String = "nonprod",
        version: Int = 1,
        key: ByteArray = KEY_A,
        settings: MaskingSettings = MaskingSettings(),
    ) = MaskingEngine(masking(domains, realm, version), MasterKey(realm, version, key), dictionaries, settings)

    fun spec(
        table: String,
        columns: Map<String, ColumnPolicy>,
    ) = TableSpec(source = table, fullCopy = true, maxFullCopyBytes = DataSize.parse("1GiB"), columns = columns)

    /** Columns as (name, ClickHouse type, policy). */
    fun masker(
        engine: MaskingEngine,
        vararg columns: Triple<String, String, ColumnPolicy>,
        table: String = "db.t",
    ): TableMasker =
        engine.compile(
            spec(table, columns.associate { it.first to it.third }),
            columns.map { ColumnSchema(it.first, TypeParser.parse(it.second)) },
        )

    fun mask(domain: String) = ColumnPolicy.Mask(domain)

    /** A one-column masker for [domain] over [type]; returns a function masking single values. */
    fun single(
        domain: Domain,
        type: String,
        engine: MaskingEngine = engine(mapOf("d" to domain)),
    ): (Any?) -> Any? {
        val masker = masker(engine, Triple("c", type, mask("d")))
        return { value -> masker.mask(arrayOf(value))[0] }
    }
}
