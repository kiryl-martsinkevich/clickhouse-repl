package chrepl.cli

import chrepl.config.model.KeyEncoding
import chrepl.masking.keys.DsmClient
import chrepl.masking.keys.KeyLoadException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * BYOK migration to Fortanix DSM (SPEC §6.2): wraps the existing static master key under a DSM KEK.
 * With `provider: fortanix`, the same `version` and this wrapped key, masked values stay identical.
 * The key material is read from a file and never printed.
 */
class DsmWrapCommand : CliktCommand(name = "dsm-wrap") {
    override fun help(context: Context) =
        "Wrap the current static masking key under a Fortanix DSM KEK, for provider: fortanix (envelope mode). Masked values do not change."

    private val endpoint by option("--endpoint", help = "DSM base URL, e.g. https://eu.smartkey.io").required()
    private val apiKeyFile by option("--api-key-file", help = "file with the DSM app API key").path(mustExist = true).required()
    private val kek by option("--kek", help = "name of the non-exportable AES KEK in DSM").required()
    private val keyFile by option("--key-file", help = "file with the static key (hex or base64)").path(mustExist = true).required()
    private val out by option("--out", help = "where to write the wrapped key (JSON)").path().required()

    override fun run() {
        val material = KeyEncoding.decode(Files.readString(keyFile).trim()) ?: fail("$keyFile is neither hex nor base64")
        try {
            if (material.size <
                KeyEncoding.MIN_BYTES
            ) {
                fail("the key in $keyFile has ${material.size * 8} bits; at least ${KeyEncoding.MIN_BYTES * 8} are required")
            }
            val wrapped = DsmClient(endpoint, Files.readString(apiKeyFile).trim()).encrypt(kek, material)
            Files.writeString(out, wrapped.toJson())
            runCatching { Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-------")) }
            echo("wrapped key written to $out (KEK $kek); reference it as masking.key.fortanix.wrapped_key")
        } catch (e: KeyLoadException) {
            fail(e.message ?: "DSM request failed")
        } finally {
            material.fill(0)
        }
    }

    private fun fail(message: String): Nothing {
        echo("error: $message", err = true)
        throw ProgramResult(1)
    }
}
