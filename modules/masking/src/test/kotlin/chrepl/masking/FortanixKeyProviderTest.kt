package chrepl.masking

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ColumnPolicy
import chrepl.config.model.FortanixKey
import chrepl.config.model.Masking
import chrepl.config.model.MaskingKey
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.masking.keys.DsmClient
import chrepl.masking.keys.FortanixKeyProvider
import chrepl.masking.keys.KeyLoadException
import chrepl.masking.keys.KeyProviders
import chrepl.masking.keys.MockDsm
import chrepl.masking.keys.StaticKeyProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.HexFormat
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** PLAN M8: the Fortanix DSM provider yields the same master key, so the same masked values, as `static`. */
class FortanixKeyProviderTest {
    private val example = Path.of(System.getProperty("chrepl.exampleConfig"))
    private val baseMasking: Masking = ConfigLoader.load(example).config!!.masking
    private val material = ByteArray(32) { (it * 11 + 3).toByte() }
    private val dsm = MockDsm().start().also { it.createKek("kek-1") }
    private val secrets = mutableMapOf<String, String>()
    private val reader = SecretReader { ref -> secrets[ref.target] }

    @AfterEach
    fun stop() = dsm.close()

    private val static: Masking =
        baseMasking.copy(key = MaskingKey("static", SecretRef.parse("\${env:STATIC_KEY}"), 1)).also {
            secrets["STATIC_KEY"] = HexFormat.of().formatHex(material)
        }

    private fun fortanix(dsmKey: FortanixKey) = baseMasking.copy(key = MaskingKey("fortanix", null, 1, dsmKey))

    private fun envelope(): Masking {
        secrets["DSM_API_KEY"] = dsm.apiKeyValue
        // BYOK migration: the existing static key is wrapped under the DSM KEK (what `clickhouse-repl dsm-wrap` does).
        secrets["WRAPPED"] = DsmClient(dsm.url, dsm.apiKeyValue).encrypt("kek-1", material).toJson()
        return fortanix(
            FortanixKey(dsm.url, SecretRef.parse("\${env:DSM_API_KEY}"), kek = "kek-1", wrappedKey = SecretRef.parse("\${env:WRAPPED}")),
        )
    }

    private fun sameKeys(other: Masking) {
        StaticKeyProvider(static, reader).load().use { a ->
            KeyProviders.create(other, reader).load().use { b ->
                for (domain in baseMasking.domains.keys) {
                    assertContentEquals(a.derive("hmac", domain), b.derive("hmac", domain), domain)
                    assertContentEquals(a.derive("ff1", domain), b.derive("ff1", domain), domain)
                }
            }
        }
    }

    @Test
    fun `envelope mode unwraps the same master key once, so masked values are identical to static`() {
        val masking = envelope()
        dsm.calls.clear()
        sameKeys(masking)
        assertEquals(1, dsm.calls["/sys/v1/session/auth"]?.get())
        assertEquals(1, dsm.calls["/crypto/v1/decrypt"]?.get())

        // And through the whole engine: one masked value per domain type.
        val dictionaries = example.parent
        MaskingEngine.create(static, StaticKeyProvider(static, reader), dictionaries).use { a ->
            MaskingEngine.create(masking, KeyProviders.create(masking, reader), dictionaries).use { b ->
                val column = Triple("email", "String", ColumnPolicy.Mask("email"))
                val left = MaskingFixtures.masker(a, column).mask(arrayOf("alice@bank.com"))[0]
                val right = MaskingFixtures.masker(b, column).mask(arrayOf("alice@bank.com"))[0]
                assertEquals(left, right)
            }
        }
    }

    @Test
    fun `export mode reads an exportable HMAC object holding the same key`() {
        dsm.importExportable("chrepl-masking-v1", material)
        secrets["DSM_API_KEY"] = dsm.apiKeyValue
        val masking = fortanix(FortanixKey(dsm.url, SecretRef.parse("\${env:DSM_API_KEY}"), keyName = "chrepl-masking-v1"))
        assertIs<FortanixKeyProvider>(KeyProviders.create(masking, reader))
        sameKeys(masking)
    }

    @Test
    fun `failures fail closed and never print key material`() {
        val masking = envelope()
        secrets["DSM_API_KEY"] = "bm90LXRoZS1rZXk="
        val refused = assertFailsWith<KeyLoadException> { KeyProviders.create(masking, reader).load() }
        assertContains(refused.message!!, "HTTP 401")
        assertFalse(refused.message!!.contains(HexFormat.of().formatHex(material)))

        secrets["DSM_API_KEY"] = dsm.apiKeyValue
        dsm.close()
        val unreachable = assertFailsWith<KeyLoadException> { KeyProviders.create(masking, reader).load() }
        assertContains(unreachable.message!!, "cannot reach Fortanix DSM")
    }

    @Test
    fun `a wrapped key made with another KEK is refused`() {
        val masking = envelope()
        val other = fortanix(masking.key.fortanix!!.copy(kek = "kek-2"))
        val e = assertFailsWith<KeyLoadException> { KeyProviders.create(other, reader).load() }
        assertContains(e.message!!, "kek-1")
    }
}
