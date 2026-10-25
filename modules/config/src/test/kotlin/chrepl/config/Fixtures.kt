package chrepl.config

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.Code
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.HostResolver
import chrepl.config.validate.Issue
import chrepl.config.validate.Severity
import chrepl.config.validate.ValidationOptions
import chrepl.config.validate.ValidationReport
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.InetAddress
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.fail

object Fixtures {
    val examplePath: Path =
        Path.of(System.getProperty("chrepl.exampleConfig") ?: error("system property chrepl.exampleConfig is not set"))

    fun exampleTree(): ObjectNode = ConfigLoader.readTree(examplePath)

    /** Binds and validates a tree, returning loading and validation issues together. */
    fun check(
        tree: ObjectNode,
        options: ValidationOptions = ValidationOptions(),
    ): ValidationReport {
        val loaded = ConfigLoader.bind(tree)
        val config = loaded.config ?: return ValidationReport(loaded.issues)
        return ValidationReport(loaded.issues) + ConfigValidator(options).validate(config, examplePath.parent)
    }

    /** A resolver that gives every host its own address in 10.30.0.0/16 unless overridden. */
    fun resolver(overrides: Map<String, String> = emptyMap()): HostResolver =
        HostResolver { host ->
            val h = host.hashCode()
            listOf(InetAddress.getByName(overrides[host] ?: "10.30.${(h ushr 8) and 0xFF}.${h and 0xFF}"))
        }
}

/** Navigates `/a/b/0/c`-style pointers into a mutable tree. */
fun ObjectNode.obj(pointer: String): ObjectNode = at(pointer) as? ObjectNode ?: fail("no object at $pointer")

fun ObjectNode.arr(pointer: String): ArrayNode = at(pointer) as? ArrayNode ?: fail("no array at $pointer")

fun ValidationReport.assertHas(
    code: Code,
    pathPrefix: String = "",
    severity: Severity = Severity.ERROR,
): Issue {
    val match = issues.firstOrNull { it.code == code && it.severity == severity && it.path.startsWith(pathPrefix) }
    return match ?: fail("expected $severity $code at '$pathPrefix*', got:\n${render()}")
}

fun ValidationReport.assertClean() = assertTrue(issues.isEmpty(), "expected no issues, got:\n${render()}")

fun ValidationReport.render(): String =
    if (issues.isEmpty()) "  (none)" else issues.joinToString("\n") { "  ${it.severity} ${it.code} ${it.path}: ${it.message}" }
