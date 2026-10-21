package chrepl.config

import chrepl.config.load.ConfigLoader
import chrepl.config.model.SecretRef
import chrepl.config.validate.Code
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.SecretReader
import chrepl.config.validate.ValidationOptions
import chrepl.config.validate.ValidationReport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val example get() = Fixtures.examplePath.readText()

    /** Splits the example at its `tables:` section into two files. */
    private fun splitExample(): Pair<String, String> {
        val start = example.indexOf("\ntables:")
        val end = example.indexOf("\ndatasets:")
        return example.substring(0, start) + example.substring(end) to example.substring(start, end)
    }

    @Test
    fun `directory files are merged in lexical order`() {
        val (rest, tables) = splitExample()
        dir.resolve("10-main.yaml").writeText(rest)
        dir.resolve("20-tables.yml").writeText(tables)
        dir.resolve("notes.txt").writeText("ignored")
        val loaded = ConfigLoader.load(dir)
        val config = assertNotNull(loaded.config, loaded.issues.toString())
        assertEquals(4, config.tables.size)
        assertEquals(dir, loaded.baseDir)
    }

    @Test
    fun `lists from several files concatenate`() {
        val (rest, tables) = splitExample()
        dir.resolve("a.yaml").writeText(rest + tables)
        dir
            .resolve(
                "b.yaml",
            ).writeText("tables:\n  - source: extra.table\n    full_copy: true\n    max_full_copy_bytes: 1GiB\n    columns: { id: pass }\n")
        assertEquals(5, assertNotNull(ConfigLoader.load(dir).config).tables.size)
    }

    @Test
    fun `the same scalar in two files is an error`() {
        dir.resolve("a.yaml").writeText(example)
        dir.resolve("b.yaml").writeText("guard:\n  poll_interval: 10s\n")
        val loaded = ConfigLoader.load(dir)
        assertNull(loaded.config)
        ValidationReport(loaded.issues).assertHas(Code.DUPLICATE_KEY, "guard.poll_interval")
    }

    @Test
    fun `duplicate keys in one file are an error`() {
        val file = dir.resolve("c.yaml")
        file.writeText(example.replace("version: 1\n", "version: 1\nversion: 1\n"))
        ValidationReport(ConfigLoader.load(file).issues).assertHas(Code.DUPLICATE_KEY)
    }

    @Test
    fun `syntax errors carry the location`() {
        val file = dir.resolve("c.yaml")
        file.writeText("version: 1\nguard: [unclosed\n")
        val issue = ValidationReport(ConfigLoader.load(file).issues).assertHas(Code.YAML_SYNTAX)
        assert("line" in issue.message) { issue.message }
    }

    @Test
    fun `missing path is reported`() {
        ValidationReport(ConfigLoader.load(dir.resolve("nope.yaml")).issues).assertHas(Code.FILE_NOT_FOUND)
    }
}

class SecretResolutionTest {
    private fun validate(secrets: Map<String, String>): ValidationReport {
        val tree = Fixtures.exampleTree()
        tree.obj("/masking/key").put("ref", "\${env:MASK_KEY}")
        val reader = SecretReader { ref -> if (ref.kind == SecretRef.Kind.ENV) secrets[ref.target] else "file-secret" }
        val config = assertNotNull(ConfigLoader.bind(tree).config)
        return ConfigValidator(ValidationOptions(resolveSecrets = true, secretReader = reader)).validate(config)
    }

    @Test
    fun `a 256-bit hex or base64 key is accepted`() {
        validate(mapOf("MASK_KEY" to "ab".repeat(32))).assertClean()
        validate(mapOf("MASK_KEY" to "q83vEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJA=")).assertClean()
    }

    @Test
    fun `a short key is rejected`() {
        validate(mapOf("MASK_KEY" to "ab".repeat(16))).assertHas(Code.KEY_TOO_SHORT, "masking.key.ref")
    }

    @Test
    fun `an unreadable secret is reported`() {
        validate(emptyMap()).assertHas(Code.SECRET_UNRESOLVED, "masking.key.ref")
    }
}
