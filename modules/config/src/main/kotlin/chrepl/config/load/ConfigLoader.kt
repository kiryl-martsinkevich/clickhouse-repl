package chrepl.config.load

import chrepl.config.model.ReplicatorConfig
import chrepl.config.validate.Code
import chrepl.config.validate.Issue
import chrepl.config.validate.Severity
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** Result of loading: the bound config when binding succeeded, and any loading/binding issues. */
data class LoadResult(
    val config: ReplicatorConfig?,
    val issues: List<Issue>,
    /** Directory relative file references (e.g. dictionaries) resolve against. */
    val baseDir: Path?,
)

/**
 * Loads YAML configuration (SPEC §9.1): a single file, or a directory whose `*.yaml` / `*.yml`
 * files are merged in lexical order. Objects merge recursively, lists concatenate, and the same
 * scalar defined in two files is an error. Binding is strict: unknown fields, missing required
 * fields and malformed values are errors.
 */
object ConfigLoader {
    const val SUPPORTED_VERSION = 1

    val mapper: YAMLMapper =
        YAMLMapper
            .builder(YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .addModule(kotlinModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()

    fun load(path: Path): LoadResult {
        val tree =
            try {
                readTree(path)
            } catch (e: ConfigLoadException) {
                return LoadResult(null, listOf(e.issue), null)
            }
        val baseDir = if (path.isDirectory()) path else path.toAbsolutePath().parent
        return bind(tree).copy(baseDir = baseDir)
    }

    /** Reads and merges YAML into one tree without binding it. */
    fun readTree(path: Path): ObjectNode {
        val files =
            when {
                path.isDirectory() ->
                    path
                        .listDirectoryEntries()
                        .filter {
                            it.isRegularFile() &&
                                it.extension in
                                setOf(
                                    "yaml",
                                    "yml",
                                )
                        }.sorted()
                path.isRegularFile() -> listOf(path)
                else -> throw ConfigLoadException(issue(Code.FILE_NOT_FOUND, "", "no such file or directory: $path"))
            }
        if (files.isEmpty()) throw ConfigLoadException(issue(Code.FILE_NOT_FOUND, "", "no *.yaml files in $path"))
        val merged = mapper.createObjectNode()
        for (file in files) {
            val node =
                try {
                    Files.newBufferedReader(file).use { mapper.readTree(it) }
                } catch (e: JsonProcessingException) {
                    val code = if (e.originalMessage.startsWith("Duplicate field")) Code.DUPLICATE_KEY else Code.YAML_SYNTAX
                    val where = e.location?.let { " (line ${it.lineNr}, column ${it.columnNr})" } ?: ""
                    throw ConfigLoadException(issue(code, "", "${file.fileName}$where: ${e.originalMessage}"))
                } catch (e: IOException) {
                    throw ConfigLoadException(issue(Code.FILE_NOT_FOUND, "", "cannot read $file: ${e.message}"))
                }
            if (node == null || node.isMissingNode || node.isNull) continue
            if (node !is ObjectNode) throw ConfigLoadException(issue(Code.YAML_SYNTAX, "", "${file.fileName}: top level must be a mapping"))
            merge(merged, node, "", file.fileName.toString())
        }
        return merged
    }

    /** Binds a merged tree to the model; never throws. */
    fun bind(tree: ObjectNode): LoadResult {
        val version = tree.get("version")
        if (version == null || !version.isInt || version.intValue() != SUPPORTED_VERSION) {
            return LoadResult(null, listOf(issue(Code.UNSUPPORTED_VERSION, "version", "version must be $SUPPORTED_VERSION")), null)
        }
        return try {
            LoadResult(mapper.treeToValue(tree, ReplicatorConfig::class.java), emptyList(), null)
        } catch (e: JsonMappingException) {
            LoadResult(null, listOf(bindingIssue(e)), null)
        } catch (e: JsonProcessingException) {
            LoadResult(null, listOf(issue(Code.INVALID_VALUE, "", e.originalMessage)), null)
        }
    }

    private fun bindingIssue(e: JsonMappingException): Issue {
        val path = formatPath(e.path)
        return when (e) {
            is UnrecognizedPropertyException -> {
                issue(Code.UNKNOWN_FIELD, path, "unknown field '${e.propertyName}'")
            }

            is ValueInstantiationException -> {
                issue(Code.INVALID_VALUE, path, e.cause?.message ?: e.originalMessage)
            }

            is InvalidFormatException -> {
                issue(Code.INVALID_VALUE, path, "invalid value '${e.value}' for ${e.targetType.simpleName}")
            }

            is MismatchedInputException ->
                if (e.originalMessage.contains("missing", ignoreCase = true) || e.originalMessage.contains("null value")) {
                    val name = Regex("""parameter (\w+)""").find(e.originalMessage)?.groupValues?.get(1)
                    val field = name?.let { snakeCase(it) }
                    val fullPath = listOfNotNull(path.ifEmpty { null }, field).joinToString(".")
                    issue(Code.MISSING_FIELD, fullPath, "required field${field?.let { " '$it'" } ?: ""} is missing")
                } else {
                    issue(Code.INVALID_VALUE, path, e.originalMessage.substringBefore('\n'))
                }

            else -> {
                issue(Code.INVALID_VALUE, path, e.originalMessage.substringBefore('\n'))
            }
        }
    }

    private fun formatPath(refs: List<JsonMappingException.Reference>): String =
        buildString {
            for (ref in refs) {
                when {
                    ref.fieldName != null -> {
                        if (isNotEmpty()) append('.')
                        append(ref.fieldName)
                    }

                    ref.index >= 0 -> {
                        append('[').append(ref.index).append(']')
                    }
                }
            }
        }

    private fun snakeCase(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private fun merge(
        into: ObjectNode,
        from: ObjectNode,
        path: String,
        file: String,
    ) {
        for ((key, value) in from.properties()) {
            val childPath = if (path.isEmpty()) key else "$path.$key"
            val existing = into.get(key)
            when {
                existing == null -> {
                    into.set<JsonNode>(key, value)
                }

                existing is ObjectNode && value is ObjectNode -> {
                    merge(existing, value, childPath, file)
                }

                existing is ArrayNode && value is ArrayNode -> {
                    existing.addAll(value)
                }

                else -> {
                    throw ConfigLoadException(issue(Code.DUPLICATE_KEY, childPath, "defined again in $file"))
                }
            }
        }
    }

    private fun issue(
        code: Code,
        path: String,
        message: String,
    ) = Issue(Severity.ERROR, code, path, message)
}

class ConfigLoadException(
    val issue: Issue,
) : Exception(issue.message)
