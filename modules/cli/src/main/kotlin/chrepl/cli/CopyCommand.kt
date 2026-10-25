package chrepl.cli

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.ValidationOptions
import chrepl.guard.GuardFactory
import chrepl.guard.GuardGate
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.KeyProviders
import chrepl.transfer.Chunk
import chrepl.transfer.ClientFactory
import chrepl.transfer.CopyJob
import chrepl.transfer.JobListener
import chrepl.transfer.UnguardedGate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Copies tables or a dataset to a target in the foreground. For a `dc: dr` source every read goes
 * through an in-process Source Guard (SPEC §4); `dc: test` sources (dev/Testcontainers) run unguarded.
 */
class CopyCommand : CliktCommand(name = "copy") {
    override fun help(context: Context) =
        "Copy tables (or a dataset) for COB dates to a target, masking on the way. Reads from DR wait for the Source Guard."

    private val configPath by option("--config", help = "config file or directory").path().required()
    private val target by option("--target", help = "target environment name, e.g. qa1").required()
    private val tables by option("--table", help = "database.table to copy (repeatable)").multiple()
    private val dataset by option("--dataset", help = "dataset name from the config")
    private val cob by option("--cob", help = "COB dates, YYYY-MM-DD, comma-separated").split(",").multiple()

    override fun run() {
        val loaded = ConfigLoader.load(configPath)
        val config =
            loaded.config ?: fail("cannot load $configPath: ${loaded.issues.joinToString { "${it.code} ${it.path}: ${it.message}" }}")
        val report = ConfigValidator(ValidationOptions(resolveSecrets = true, checkFiles = true)).validate(config, loaded.baseDir)
        if (!report.isValid) {
            fail(
                "configuration is invalid:\n" + report.errors.joinToString("\n") { "  ${it.code} ${it.path}: ${it.message}" },
            )
        }
        val source = config.sources.values.single()

        val names =
            tables +
                (
                    dataset?.let { name ->
                        config.datasets.firstOrNull { it.name == name }?.tables ?: fail("unknown dataset '$name'")
                    } ?: emptyList()
                )
        if (names.isEmpty()) fail("give --table or --dataset")
        val dates =
            try {
                cob.flatten().map { LocalDate.parse(it.trim()) }
            } catch (e: DateTimeParseException) {
                fail("invalid COB date: ${e.parsedString}")
            }

        val guard = if (source.dc == "test") null else GuardFactory.create(config.guard, source, ClientFactory()).start()
        val gate =
            guard?.let {
                GuardGate(it) { _, reasons -> echo("  waiting for the source: ${reasons.joinToString("; ")}") }
            } ?: UnguardedGate
        MaskingEngine.create(config.masking, KeyProviders.create(config.masking), loaded.baseDir).use { engine ->
            val listener =
                object : JobListener {
                    override fun chunkPreempted(
                        chunk: Chunk,
                        attempt: Int,
                        reason: String,
                    ) = echo("  chunk ${chunk.id} pre-empted (attempt $attempt): $reason")
                }
            val job = CopyJob(config, target, engine, gate, listener = listener)
            echo("job ${job.jobId}: ${names.size} table(s) to $target, COB ${dates.ifEmpty { listOf("n/a") }.joinToString()}")
            for (name in names.distinct()) {
                val result = job.copy(name, dates)
                echo(
                    "  ${result.table}: ${result.rowsCopied} rows in ${result.partitions.size} partition(s), " +
                        "${result.chunks} chunk(s), ${result.duration.toMillis()} ms",
                )
            }
        }
        guard?.close()
    }

    private fun fail(message: String): Nothing {
        echo(message, err = true)
        throw ProgramResult(ExitCodes.INVALID)
    }
}
