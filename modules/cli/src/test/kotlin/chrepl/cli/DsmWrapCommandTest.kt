package chrepl.cli

import chrepl.masking.keys.DsmClient
import chrepl.masking.keys.MockDsm
import chrepl.masking.keys.WrappedKey
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DsmWrapCommandTest {
    @TempDir
    lateinit var dir: Path

    private val dsm = MockDsm().start().also { it.createKek("kek-1") }

    @AfterEach
    fun stop() = dsm.close()

    @Test
    fun `wraps the static key so that DSM unwraps the same bytes`() {
        val material = ByteArray(32) { (it * 7).toByte() }
        val hex = HexFormat.of().formatHex(material)
        val keyFile = dir.resolve("key").also { it.writeText(hex) }
        val apiKey = dir.resolve("api-key").also { it.writeText(dsm.apiKeyValue) }
        val out = dir.resolve("wrapped.json")
        val result =
            cli().test(
                listOf(
                    "dsm-wrap",
                    "--endpoint",
                    dsm.url,
                    "--api-key-file",
                    "$apiKey",
                    "--kek",
                    "kek-1",
                    "--key-file",
                    "$keyFile",
                    "--out",
                    "$out",
                ),
            )
        assertEquals(0, result.statusCode, result.stderr)
        assertFalse(result.output.contains(hex), "the key must never be printed")
        val wrapped = WrappedKey.parse(out.readText())
        assertEquals("kek-1", wrapped.kek)
        assertFalse(Files.readString(out).contains(hex))
        assertContentEquals(material, DsmClient(dsm.url, dsm.apiKeyValue).decrypt(wrapped))
    }

    @Test
    fun `a short key is refused`() {
        val keyFile = dir.resolve("key").also { it.writeText("00ff") }
        val apiKey = dir.resolve("api-key").also { it.writeText(dsm.apiKeyValue) }
        val result =
            cli().test(
                listOf(
                    "dsm-wrap",
                    "--endpoint",
                    dsm.url,
                    "--api-key-file",
                    "$apiKey",
                    "--kek",
                    "kek-1",
                    "--key-file",
                    "$keyFile",
                    "--out",
                    "${dir.resolve("o")}",
                ),
            )
        assertEquals(1, result.statusCode)
    }
}
