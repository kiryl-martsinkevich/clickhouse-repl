package chrepl.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliTest {
    @TempDir
    lateinit var dir: Path

    private val example = Path.of(System.getProperty("chrepl.exampleConfig"))

    @Test
    fun `valid example exits 0`() {
        val result = cli().test(listOf("validate-config", example.toString()))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "valid (0 errors, 0 warnings)")
    }

    @Test
    fun `active-DC host exits 1 and names the rule`() {
        val broken = dir.resolve("broken.yaml")
        broken.writeText(example.readText().replace("host: ch-dr-s1.dc-dr.internal:8443", "host: ch-prod-s1.dc-prod.internal:8443"))
        val result = cli().test(listOf("validate-config", broken.toString()))
        assertEquals(ExitCodes.INVALID, result.statusCode, result.output)
        assertContains(result.output, "ACTIVE_DC_HOST")
        assertContains(result.output, "sources.dr.shards[0].host")
        assertContains(result.output, "invalid (1 error, 0 warnings)")
    }

    @Test
    fun `missing file exits 2`() {
        val result = cli().test(listOf("validate-config", dir.resolve("absent.yaml").toString()))
        assertEquals(ExitCodes.UNREADABLE, result.statusCode, result.output)
        assertContains(result.output, "FILE_NOT_FOUND")
    }

    @Test
    fun `json output is machine readable`() {
        val broken = dir.resolve("broken.yaml")
        broken.writeText(example.readText().replace("    environment_marker: uat\n", "    environment_marker: production\n"))
        val result = cli().test(listOf("validate-config", "--format", "json", broken.toString()))
        assertEquals(ExitCodes.INVALID, result.statusCode, result.output)
        val json = ObjectMapper().readTree(result.stdout)
        assertEquals(false, json["valid"].booleanValue())
        assertEquals("TARGET_MARKER", json["issues"][0]["code"].textValue())
        assertEquals("targets.uat.environment_marker", json["issues"][0]["path"].textValue())
    }
}

class CopyCommandTest {
    private val example = Path.of(System.getProperty("chrepl.exampleConfig"))

    @Test
    fun `copy refuses a DR source because it would bypass the guard`() {
        val result =
            cli().test(
                listOf("copy", "--config", example.toString(), "--target", "qa1", "--table", "trading.trades", "--cob", "2026-10-19"),
                envvars = emptyMap(),
            )
        assertEquals(ExitCodes.INVALID, result.statusCode, result.output)
    }

    @Test
    fun `copy needs a config`() {
        val result = cli().test(listOf("copy", "--target", "qa1"))
        assertEquals(1, result.statusCode, result.output)
        assertContains(result.output, "--config")
    }
}
