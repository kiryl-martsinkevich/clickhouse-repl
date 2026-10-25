package chrepl.masking.table

import chrepl.config.model.Domain
import chrepl.masking.Canonical
import chrepl.masking.MaskingCollisionException
import chrepl.masking.strategy.ScalarStrategy
import chrepl.types.ChType

/** One masking domain inside a [TableMasker]: normalisation, the strategy, and the per-chunk collision check. */
internal class DomainMasker(
    val name: String,
    private val config: Domain,
    val strategy: ScalarStrategy,
    collisionLimit: Int,
) {
    private val tracker = if (config.collisionCheck) CollisionTracker(name, collisionLimit) else null

    fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any? {
        val input = if (value is String && config.normalise.isNotEmpty()) Canonical.normalise(value, config.normalise) else value
        val output = strategy.mask(input, type, entity)
        if (tracker != null && output != null) tracker.record(Canonical.of(input), Canonical.of(output))
        return output
    }

    fun resetChunk() {
        tracker?.reset()
    }

    val collisionCheckTruncated: Boolean get() = tracker?.truncated ?: false
}

/**
 * Detects two different inputs producing the same output within one chunk (SPEC §6.3). Bounded:
 * after [limit] distinct outputs it stops tracking for the rest of the chunk and reports [truncated].
 */
internal class CollisionTracker(
    private val domain: String,
    private val limit: Int,
) {
    private val seen = HashMap<String, String>()
    var truncated = false
        private set

    fun record(
        input: String,
        output: String,
    ) {
        if (truncated) return
        val previous = seen.putIfAbsent(output, input)
        if (previous != null && previous != input) throw MaskingCollisionException(domain)
        if (seen.size >= limit) {
            truncated = true
            seen.clear()
        }
    }

    fun reset() {
        seen.clear()
        truncated = false
    }
}

/** A small LRU cache of masked values; not thread-safe. */
internal class LruCache<K, V>(
    private val capacity: Int,
) : LinkedHashMap<K, V>(minOf(capacity, 1024), 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
}
