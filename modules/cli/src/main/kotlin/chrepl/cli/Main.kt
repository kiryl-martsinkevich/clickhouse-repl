package chrepl.cli

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.Code
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.ValidationOptions
import chrepl.config.validate.ValidationReport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path

/** Exit codes: 0 valid (warnings allowed), 1 invalid config, 2 config cannot be read. */
object ExitCodes {
    const val INVALID = 1
    const val UNREADABLE = 2
}

class ClickhouseRepl : CliktCommand(name = "clickhouse-repl") {
    override fun help(context: Context) = "Replicates masked data from the passive DR ClickHouse cluster to non-production environments."

    override fun run() = Unit
}

class ValidateConfig : CliktCommand(name = "validate-config") {
    override fun help(context: Context) =
        "Validate a configuration file or directory (docs/SPEC.md section 9.3). Offline by default; " +
            "the flags enable checks that need DNS, secrets or the file system."

    private val path by argument(help = "config file, or a directory of *.yaml files merged in lexical order").path()
    private val resolveDns by option("--resolve-dns", help = "resolve hosts and check them against the active-DC CIDRs").flag()
    private val resolveSecrets by option("--resolve-secrets", help = "read secrets and check the masking key length").flag()
    private val checkFiles by option("--check-files", help = "check that referenced files (dictionaries) exist").flag()
    private val format by option("--format", help = "output format").choice("text", "json").default("text")

    override fun run() {
        val loaded = ConfigLoader.load(path)
        val config = loaded.config
        val report =
            if (config == null) {
                ValidationReport(loaded.issues)
            } else {
                val options = ValidationOptions(resolveDns = resolveDns, resolveSecrets = resolveSecrets, checkFiles = checkFiles)
                ConfigValidator(options).validate(config, loaded.baseDir)
            }
        if (format == "json") echo(json(report)) else echo(text(report))
        when {
            report.issues.any { it.code == Code.FILE_NOT_FOUND } -> throw ProgramResult(ExitCodes.UNREADABLE)
            !report.isValid -> throw ProgramResult(ExitCodes.INVALID)
        }
    }

    private fun text(report: ValidationReport): String =
        buildString {
            for (issue in report.issues) {
                val where = if (issue.path.isEmpty()) "" else "${issue.path}: "
                appendLine("%-7s %-26s %s%s".format(issue.severity, issue.code, where, issue.message))
            }
            val verdict = if (report.isValid) "valid" else "invalid"
            append("$path: $verdict (${plural(report.errors.size, "error")}, ${plural(report.warnings.size, "warning")})")
        }

    private fun json(report: ValidationReport): String =
        ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
            mapOf(
                "path" to path.toString(),
                "valid" to report.isValid,
                "errors" to report.errors.size,
                "warnings" to report.warnings.size,
                "issues" to
                    report.issues.map {
                        mapOf("severity" to it.severity.name, "code" to it.code.name, "path" to it.path, "message" to it.message)
                    },
            ),
        )

    private fun plural(
        n: Int,
        word: String,
    ) = if (n == 1) "1 $word" else "$n ${word}s"
}

fun cli(): CliktCommand =
    ClickhouseRepl().subcommands(
        ValidateConfig(),
        CopyCommand(),
        SubmitCommand(),
        DryRunCommand(),
        StatusCommand(),
        JobsCommand(),
        CancelCommand(),
        RekeyCommand(),
        DsmWrapCommand(),
    )

fun main(args: Array<String>) = cli().main(args)
