package chrepl.config.model

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

/**
 * Masking policy of one column (SPEC §6.1). Written in YAML as:
 * - `pass`
 * - `{ mask: <domain>, entity_column: <column> }`
 * - `{ map_keys: <policy>, map_values: { <key>: <policy> }, default: <policy> }` for `Map` columns
 * - `{ json_paths: { $.a.b: <policy> }, default: <policy> }` for JSON / JSON-in-String columns
 */
@JsonDeserialize(using = ColumnPolicyDeserializer::class)
sealed interface ColumnPolicy {
    /** True when the column (or any part of it) is transformed. */
    val isMasked: Boolean

    data object Pass : ColumnPolicy {
        override val isMasked = false
    }

    data class Mask(
        val domain: String,
        val entityColumn: String? = null,
    ) : ColumnPolicy {
        override val isMasked = true
    }

    data class MapPolicy(
        val mapKeys: ColumnPolicy?,
        val mapValues: Map<String, ColumnPolicy>,
        val default: ColumnPolicy,
    ) : ColumnPolicy {
        override val isMasked: Boolean get() = (mapKeys?.isMasked ?: false) || mapValues.values.any { it.isMasked } || default.isMasked
    }

    data class JsonPaths(
        val paths: Map<String, ColumnPolicy>,
        val default: ColumnPolicy,
    ) : ColumnPolicy {
        override val isMasked: Boolean get() = paths.values.any { it.isMasked } || default.isMasked
    }
}

internal class ColumnPolicyDeserializer : StdDeserializer<ColumnPolicy>(ColumnPolicy::class.java) {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): ColumnPolicy = parse(p.codec.readTree(p), ctxt, "")

    private fun parse(
        node: JsonNode,
        ctxt: DeserializationContext,
        where: String,
    ): ColumnPolicy {
        if (node.isTextual) {
            if (node.textValue() == "pass") return ColumnPolicy.Pass
            fail(ctxt, where, "unknown column policy '${node.textValue()}' (use pass or { mask: <domain> })")
        }
        if (!node.isObject) fail(ctxt, where, "column policy must be 'pass' or an object")
        val keys = node.fieldNames().asSequence().toSet()
        return when {
            "mask" in keys -> {
                checkKeys(ctxt, where, keys, setOf("mask", "entity_column"))
                val domain = node.get("mask")
                if (!domain.isTextual || domain.textValue().isBlank()) fail(ctxt, where, "'mask' must name a masking domain")
                ColumnPolicy.Mask(domain.textValue(), node.get("entity_column")?.textValue())
            }

            "json_paths" in keys -> {
                checkKeys(ctxt, where, keys, setOf("json_paths", "default"))
                ColumnPolicy.JsonPaths(
                    paths = children(node.get("json_paths"), ctxt, "$where.json_paths"),
                    default = node.get("default")?.let { parse(it, ctxt, "$where.default") } ?: ColumnPolicy.Pass,
                )
            }

            "map_values" in keys || "map_keys" in keys -> {
                checkKeys(ctxt, where, keys, setOf("map_keys", "map_values", "default"))
                ColumnPolicy.MapPolicy(
                    mapKeys = node.get("map_keys")?.let { parse(it, ctxt, "$where.map_keys") },
                    mapValues = node.get("map_values")?.let { children(it, ctxt, "$where.map_values") } ?: emptyMap(),
                    default = node.get("default")?.let { parse(it, ctxt, "$where.default") } ?: ColumnPolicy.Pass,
                )
            }

            else -> fail(ctxt, where, "column policy object needs one of: mask, map_keys/map_values, json_paths")
        }
    }

    private fun children(
        node: JsonNode,
        ctxt: DeserializationContext,
        where: String,
    ): Map<String, ColumnPolicy> {
        if (!node.isObject) fail(ctxt, where, "expected a mapping of name to column policy")
        return node.properties().associate { (name, child) -> name to parse(child, ctxt, "$where.$name") }
    }

    private fun checkKeys(
        ctxt: DeserializationContext,
        where: String,
        keys: Set<String>,
        allowed: Set<String>,
    ) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            fail(
                ctxt,
                where,
                "unknown field(s) ${unknown.sorted().joinToString()} (allowed: ${allowed.joinToString()})",
            )
        }
    }

    private fun fail(
        ctxt: DeserializationContext,
        where: String,
        message: String,
    ): Nothing {
        val prefix = if (where.isEmpty()) "" else "${where.removePrefix(".")}: "
        throw JsonMappingException.from(ctxt, prefix + message)
    }
}
