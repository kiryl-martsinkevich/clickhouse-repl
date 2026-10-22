package chrepl.masking.table

import chrepl.config.model.ColumnPolicy
import chrepl.config.model.TableSpec
import chrepl.config.model.UnlistedColumns
import chrepl.masking.Canonical
import chrepl.masking.MaskingConfigException
import chrepl.masking.MaskingException
import chrepl.masking.defaultValue
import chrepl.masking.strategy.ConstantStrategy
import chrepl.masking.strategy.DateShiftStrategy
import chrepl.masking.strategy.NumericNoiseStrategy
import chrepl.masking.types.ChType
import chrepl.masking.types.isNullable
import chrepl.masking.types.scalar
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.BigIntegerNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import java.math.BigInteger

data class ColumnSchema(
    val name: String,
    val type: ChType,
)

/** Masks one value; [row] is the original (unmasked) row, for entity lookups. */
internal typealias ValueFn = (value: Any?, row: Array<Any?>) -> Any?

/**
 * Masks rows of one table (SPEC §6). Compiled from a table spec and the table's actual schema by
 * [chrepl.masking.MaskingEngine.compile]; not thread-safe, so each worker thread uses its own.
 * Entity columns are read from the original row, so an entity column may itself be masked.
 */
class TableMasker internal constructor(
    val table: String,
    val columns: List<ColumnSchema>,
    private val functions: Array<ValueFn?>,
    private val domains: Collection<DomainMasker>,
) {
    /** Names of columns that are transformed (all others are copied unchanged). */
    val maskedColumns: List<String> = columns.filterIndexed { i, _ -> functions[i] != null }.map { it.name }

    /** Returns a masked copy of [row], whose values are in [columns] order. */
    fun mask(row: Array<Any?>): Array<Any?> {
        if (row.size != columns.size) throw MaskingException("row of $table has ${row.size} values, expected ${columns.size}")
        val out = row.copyOf()
        for (i in functions.indices) {
            val f = functions[i] ?: continue
            try {
                out[i] = f(row[i], row)
            } catch (e: MaskingException) {
                throw e.withContext("$table.${columns[i].name}")
            } catch (e: RuntimeException) {
                // Never include the value: only the column and the failure class.
                throw MaskingException("$table.${columns[i].name}: masking failed (${e::class.simpleName})")
            }
        }
        return out
    }

    /** Starts a new chunk: resets per-chunk collision checks. */
    fun startChunk() = domains.forEach { it.resetChunk() }

    /** Domains whose collision check stopped early in the current chunk because it grew too large. */
    val truncatedCollisionChecks: List<String> get() = domains.filter { it.collisionCheckTruncated }.map { it.name }
}

/** Compiles column policies against concrete types; collects every problem before failing. */
internal class TableCompiler(
    private val spec: TableSpec,
    private val schema: List<ColumnSchema>,
    private val domainMasker: (String) -> DomainMasker?,
    private val cacheSize: Int,
) {
    private val problems = mutableListOf<String>()
    private val index = schema.withIndex().associate { (i, c) -> c.name to i }
    private val usedDomains = linkedMapOf<String, DomainMasker>()

    fun compile(): TableMasker {
        for (name in spec.columns.keys - index.keys) problems += "column '$name' is configured but does not exist in ${spec.source}"
        val functions =
            Array(schema.size) { i ->
                val column = schema[i]
                val policy = spec.columns[column.name]
                when {
                    policy != null -> {
                        compile(policy, column.type, column.name)
                    }

                    spec.unlistedColumns == UnlistedColumns.PASS -> {
                        null
                    }

                    else -> {
                        problems += "column '${column.name}' of ${spec.source} has no masking policy (unclassified)"
                        null
                    }
                }
            }
        if (problems.isNotEmpty()) throw MaskingConfigException(problems.map { "${spec.source}: $it" })
        return TableMasker(spec.source, schema, functions, usedDomains.values)
    }

    private fun compile(
        policy: ColumnPolicy,
        type: ChType,
        path: String,
    ): ValueFn? =
        when (policy) {
            ColumnPolicy.Pass -> null
            is ColumnPolicy.Mask -> compileMask(policy, type, path)
            is ColumnPolicy.MapPolicy -> compileMap(policy, type, path)
            is ColumnPolicy.JsonPaths -> compileJson(policy, type, path)
        }

    private fun domainFor(
        policy: ColumnPolicy.Mask,
        path: String,
    ): DomainMasker? {
        usedDomains[policy.domain]?.let { return it }
        val dm = domainMasker(policy.domain)
        if (dm == null) {
            problems += "$path: unknown masking domain '${policy.domain}'"
            return null
        }
        usedDomains[policy.domain] = dm
        return dm
    }

    private fun entityIndex(
        policy: ColumnPolicy.Mask,
        path: String,
    ): Int? {
        val name = policy.entityColumn ?: return null
        val i = index[name]
        if (i == null) {
            problems += "$path: entity column '$name' does not exist"
            return null
        }
        val type = schema[i].type.scalar()
        if (type is ChType.ArrayType ||
            type is ChType.MapType ||
            type is ChType.TupleType ||
            type is ChType.JsonType ||
            type is ChType.Unsupported
        ) {
            problems += "$path: entity column '$name' must be a scalar column, not $type"
        }
        return i
    }

    private fun compileMask(
        policy: ColumnPolicy.Mask,
        type: ChType,
        path: String,
    ): ValueFn? {
        val dm = domainFor(policy, path) ?: return null
        val strategy = dm.strategy
        if (policy.entityColumn != null && strategy !is DateShiftStrategy && strategy !is NumericNoiseStrategy) {
            problems += "$path: entity_column does not apply to masking domain '${dm.name}'"
        }
        return compileTyped(dm, type, entityIndex(policy, path), allowNull = false, path = path)
    }

    private fun compileTyped(
        dm: DomainMasker,
        type: ChType,
        entity: Int?,
        allowNull: Boolean,
        path: String,
    ): ValueFn? =
        when (type) {
            is ChType.NullableType -> {
                compileTyped(dm, type.inner, entity, allowNull = true, path = path)
            }

            is ChType.LowCardinalityType -> {
                compileTyped(dm, type.inner, entity, allowNull, path)
            }

            is ChType.ArrayType -> {
                val element = compileTyped(dm, type.element, entity, type.element.isNullable, path)
                element?.let { f -> { v, row -> (v as List<*>?)?.map { f(it, row) } } }
            }

            is ChType.TupleType -> {
                val elements = type.elements.map { compileTyped(dm, it, entity, it.isNullable, path) }
                if (elements.any { it == null }) {
                    null
                } else {
                    { v, row -> (v as List<*>?)?.mapIndexed { i, e -> elements[i]!!(e, row) } }
                }
            }

            is ChType.MapType -> {
                problems += "$path: use map_keys / map_values policies for Map columns"
                null
            }

            ChType.JsonType -> {
                compileJson(ColumnPolicy.JsonPaths(emptyMap(), ColumnPolicy.Mask(dm.name)), type, path)
            }

            else -> {
                compileScalar(dm, type, entity, allowNull, path)
            }
        }

    private fun compileScalar(
        dm: DomainMasker,
        type: ChType,
        entity: Int?,
        allowNull: Boolean,
        path: String,
    ): ValueFn? {
        val strategy = dm.strategy
        strategy.unsupported(type)?.let {
            problems += "$path: $it (domain '${dm.name}')"
            return null
        }
        val default by lazy { defaultValue(type) }
        val cache = if (strategy.cacheable && cacheSize > 0) LruCache<Any, Any>(cacheSize) else null
        val constant = strategy as? ConstantStrategy
        return { value, row ->
            if (value == null) {
                constant?.mask(Unit, type, null)
            } else {
                val entityValue = entity?.let { row[it]?.let(Canonical::of) }
                val key: Any = if (entityValue == null) value else Pair(value, entityValue)
                val masked = cache?.get(key) ?: dm.mask(value, type, entityValue)?.also { cache?.put(key, it) }
                masked ?: if (allowNull) null else default
            }
        }
    }

    private fun compileMap(
        policy: ColumnPolicy.MapPolicy,
        type: ChType,
        path: String,
    ): ValueFn? {
        val mapType = type.scalar() as? ChType.MapType
        if (mapType == null) {
            problems += "$path: map_keys / map_values apply to Map columns, not $type"
            return null
        }
        val keyFn = policy.mapKeys?.let { compile(it, mapType.key, "$path.map_keys") }
        val valueFns = policy.mapValues.mapValues { (key, p) -> compile(p, mapType.value, "$path.map_values.$key") }
        val defaultFn = compile(policy.default, mapType.value, "$path.default")
        if (keyFn == null && valueFns.values.all { it == null } && defaultFn == null) return null
        return { value, row ->
            (value as Map<*, *>?)?.let { map ->
                val out = LinkedHashMap<Any?, Any?>(map.size)
                for ((k, v) in map) {
                    val newKey = if (keyFn == null) k else keyFn(k, row)
                    val f = if (valueFns.containsKey(k.toString())) valueFns[k.toString()] else defaultFn
                    if (out.containsKey(newKey)) throw MaskingException("masked map keys collided")
                    out[newKey] = if (f == null) v else f(v, row)
                }
                out
            }
        }
    }

    // ------------------------------------------------------------------------------------- JSON

    private fun compileJson(
        policy: ColumnPolicy.JsonPaths,
        type: ChType,
        path: String,
    ): ValueFn? {
        val scalar = type.scalar()
        if (scalar != ChType.StringType && scalar !is ChType.FixedStringType && scalar != ChType.JsonType) {
            problems += "$path: json_paths apply to String or JSON columns, not $type"
            return null
        }
        val rules = policy.paths.map { (p, rule) -> JsonPath.parse(p) to leafPolicy(rule, "$path.json_paths.$p") }
        val default = leafPolicy(policy.default, "$path.default")
        if (rules.all { it.second == null } && default == null) return null
        val masker = JsonMasker(rules, default)
        return { value, row -> (value as String?)?.let { masker.mask(it, row) } }
    }

    /** Leaf maskers for the three JSON scalar kinds; null when the policy is `pass`. */
    private fun leafPolicy(
        policy: ColumnPolicy,
        path: String,
    ): JsonLeaf? =
        when (policy) {
            ColumnPolicy.Pass -> {
                null
            }

            is ColumnPolicy.Mask -> {
                domainFor(policy, path)?.let { dm ->
                    val entity = entityIndex(policy, path)

                    fun forType(t: ChType): ValueFn? =
                        if (dm.strategy.unsupported(t) == null) compileScalar(dm, t, entity, allowNull = true, path = path) else null
                    JsonLeaf(dm.name, forType(ChType.StringType), forType(JSON_INT), forType(ChType.FloatType(64)))
                }
            }

            else -> {
                problems += "$path: only pass or { mask: … } policies are allowed inside json_paths"
                null
            }
        }

    private companion object {
        val JSON_INT = ChType.IntType(256, true)
    }
}

internal class JsonLeaf(
    val domain: String,
    val text: ValueFn?,
    val integer: ValueFn?,
    val float: ValueFn?,
)

/** `$.a.b[*].c` style paths: field names, `[*]` for any array element, `[n]` for one. */
internal class JsonPath private constructor(
    val segments: List<String>,
) {
    fun matches(concrete: List<String>): Boolean =
        concrete.size == segments.size &&
            segments.indices.all { segments[it] == concrete[it] || (segments[it] == "[*]" && concrete[it].startsWith("[")) }

    companion object {
        fun parse(text: String): JsonPath {
            require(text.startsWith("$")) { "JSON path must start with $" }
            val segments = mutableListOf<String>()
            val regex = Regex("""\.([^.\[]+)|(\[(?:\*|\d+)])""")
            var pos = 1
            while (pos < text.length) {
                val m = regex.matchAt(text, pos) ?: throw IllegalArgumentException("invalid JSON path '$text'")
                segments += m.groupValues[1].ifEmpty { m.groupValues[2] }
                pos = m.range.last + 1
            }
            return JsonPath(segments)
        }
    }
}

internal class JsonMasker(
    private val rules: List<Pair<JsonPath, JsonLeaf?>>,
    private val default: JsonLeaf?,
) {
    fun mask(
        text: String,
        row: Array<Any?>,
    ): String {
        val tree =
            try {
                MAPPER.readTree(text)
            } catch (e: Exception) {
                throw MaskingException("value is not valid JSON")
            }
        return MAPPER.writeValueAsString(walk(tree, mutableListOf(), default, row))
    }

    private fun walk(
        node: JsonNode,
        path: MutableList<String>,
        inherited: JsonLeaf?,
        row: Array<Any?>,
    ): JsonNode {
        val rule = rules.lastOrNull { it.first.matches(path) }
        val leaf = if (rule != null) rule.second else inherited
        return when {
            node.isObject -> {
                val out = JsonNodeFactory.instance.objectNode()
                for ((name, child) in node.properties()) {
                    path += name
                    out.set<JsonNode>(name, walk(child, path, leaf, row))
                    path.removeAt(path.size - 1)
                }
                out
            }

            node.isArray -> {
                val out = JsonNodeFactory.instance.arrayNode()
                for ((i, child) in node.withIndex()) {
                    path += "[$i]"
                    out.add(walk(child, path, leaf, row))
                    path.removeAt(path.size - 1)
                }
                out
            }

            leaf == null || node.isNull -> {
                node
            }

            node.isTextual -> {
                apply(leaf.text, leaf, node.textValue(), row)?.let { TextNode.valueOf(it as String) } ?: NullNode.instance
            }

            node.isIntegralNumber -> {
                when (val r = apply(leaf.integer, leaf, node.bigIntegerValue(), row)) {
                    null -> NullNode.instance
                    is BigInteger -> if (r.bitLength() < 64) LongNode.valueOf(r.toLong()) else BigIntegerNode.valueOf(r)
                    else -> LongNode.valueOf((r as Number).toLong())
                }
            }

            node.isNumber -> {
                apply(leaf.float, leaf, node.doubleValue(), row)?.let { DoubleNode.valueOf(it as Double) } ?: NullNode.instance
            }

            else -> {
                node // booleans carry no identifying detail and are kept
            }
        }
    }

    private fun apply(
        f: ValueFn?,
        leaf: JsonLeaf,
        value: Any,
        row: Array<Any?>,
    ): Any? {
        f ?: throw MaskingException("masking domain '${leaf.domain}' cannot mask this JSON value type")
        return f(value, row)
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}
