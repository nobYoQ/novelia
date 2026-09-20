package cc.novelia.app.data.cache

import java.io.File

/** Both weight and entry count are bounded, including when a single item is unusually large. */
internal class WeightedMemoryCache<K, V>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weigh: (V) -> Long
) {
    private data class Entry<V>(val value: V, val weight: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private var weight = 0L

    init { require(maxEntries > 0 && maxWeight > 0) }

    @Synchronized operator fun get(key: K): V? = entries[key]?.value

    @Synchronized fun put(key: K, value: V) {
        entries.remove(key)?.let { weight -= it.weight }
        val itemWeight = weigh(value).coerceAtLeast(1)
        if (itemWeight > maxWeight) return
        entries[key] = Entry(value, itemWeight)
        weight += itemWeight
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || weight > maxWeight) {
            weight -= iterator.next().value.weight
            iterator.remove()
        }
    }

    @Synchronized fun remove(key: K) { entries.remove(key)?.let { weight -= it.weight } }
    @Synchronized fun clear() { entries.clear(); weight = 0 }
}

/** Builds the disk index once, then maintains size and access order incrementally. */
internal class ChapterCacheIndex(private val directory: File, private val maxBytes: Long) {
    private data class Entry(val file: File, val bytes: Long, var touchedAt: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var initialized = false
    private var bytes = 0L

    init { require(maxBytes > 0) }

    @Synchronized fun accessed(file: File) {
        initialize()
        val entry = entries[file.name] ?: return
        val now = System.currentTimeMillis()
        // Preserve useful LRU order across launches without a disk metadata write on every hit.
        if (now - entry.touchedAt >= 60_000 && file.setLastModified(now)) entry.touchedAt = now
    }

    /** Returns evicted names so the decoded memory cache cannot retain deleted disk entries. */
    @Synchronized fun written(file: File): List<String> {
        initialize()
        entries.remove(file.name)?.let { bytes -= it.bytes }
        val entry = Entry(file, file.length(), file.lastModified())
        entries[file.name] = entry
        bytes += entry.bytes
        val evicted = mutableListOf<String>()
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            if (oldest.value.file.delete() || !oldest.value.file.exists()) {
                bytes -= oldest.value.bytes
                evicted += oldest.key
                iterator.remove()
            }
        }
        return evicted
    }

    @Synchronized fun removed(file: File) {
        initialize()
        entries.remove(file.name)?.let { bytes -= it.bytes }
    }

    @Synchronized fun size(): Long { initialize(); return bytes }

    @Synchronized fun reset() { entries.clear(); bytes = 0; initialized = false }

    private fun initialize() {
        if (initialized) return
        directory.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.lastModified() }?.forEach { file ->
                val entry = Entry(file, file.length(), file.lastModified())
                entries[file.name] = entry
                bytes += entry.bytes
            }
        initialized = true
    }
}
