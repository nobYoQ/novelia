package cc.novelia.app.data.cache

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeSet

/**
 * 有容量上限的元数据响应缓存，调用方生成包含账号身份的哈希键。
 * 文件修改时间表示获取响应的时间，读取命中不会延长有效期；淘汰也按获取时间排序。
 * 与正文缓存的访问时间不同，此时间参与写操作后的失效判断，不能随访问更新。
 * 热响应正文使用小容量内存缓存；有序索引增量维护磁盘淘汰顺序，避免写满后反复全量排序。
 */
class MetadataCache(private val directory: File, private val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxBytes > 0) }
    private data class Entry(val file: File, val bytes: Long, val fetchedAt: Long)
    private data class CachedText(val text: String, val bytes: Long, val fetchedAt: Long)
    private val entries = mutableMapOf<String, Entry>()
    private val evictionOrder = TreeSet(compareBy<Entry> { it.fetchedAt }.thenBy { it.file.name })
    private val memory = WeightedMemoryCache<String, CachedText>(16, 2L * 1024 * 1024) { 128L + it.text.length * 2L }
    private var initialized = false
    private var total = 0L
    private var invalidatedAt = 0L

    /** 仅返回未过期且晚于本地/调用方失效时间的内容；时钟回拨形成的未来缓存也视为未命中。 */
    @Synchronized fun read(key: String, now: Long = System.currentTimeMillis(), maxAgeMillis: Long = Long.MAX_VALUE, newerThan: Long = 0): String? {
        val file = file(key)
        initialize()
        val fetchedAt = file.lastModified()
        val age = now - fetchedAt
        if (!file.isFile || age < 0 || age > maxAgeMillis || fetchedAt <= maxOf(newerThan, invalidatedAt)) return null
        val bytes = file.length()
        memory[key]?.takeIf { it.fetchedAt == fetchedAt && it.bytes == bytes }?.let { return it.text }
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.also {
            memory.put(key, CachedText(it, bytes, fetchedAt))
        }
    }

    /** 单独原子保存失效时间，使进程重启后仍不会读取写操作之前的旧响应，无需重写每份缓存。 */
    @Synchronized fun invalidate(timestamp: Long) {
        initialize()
        directory.mkdirs()
        val pending = File.createTempFile("invalidate-", ".tmp", directory)
        try {
            pending.writeText(timestamp.toString(), Charsets.UTF_8)
            Files.move(pending.toPath(), File(directory, "invalidated-at").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            invalidatedAt = timestamp
            memory.clear()
        } finally { pending.delete() }
    }

    @Synchronized fun write(key: String, text: String, fetchedAt: Long = System.currentTimeMillis()) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return
        directory.mkdirs()
        initialize()
        val destination = file(key)
        val pending = File.createTempFile("metadata-", ".tmp", directory)
        try {
            pending.writeBytes(bytes)
            check(pending.setLastModified(fetchedAt)) { "无法记录缓存时间" }
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        entries.remove(destination.name)?.let { total -= it.bytes; evictionOrder.remove(it) }
        val entry = Entry(destination, bytes.size.toLong(), fetchedAt)
        entries[destination.name] = entry
        evictionOrder.add(entry)
        memory.put(key, CachedText(text, entry.bytes, fetchedAt))
        total += bytes.size
        if (total > maxBytes) {
            val iterator = evictionOrder.iterator()
            while (total > maxBytes && iterator.hasNext()) {
                val oldest = iterator.next()
                if (oldest.file.delete() || !oldest.file.exists()) {
                    total -= oldest.bytes
                    entries.remove(oldest.file.name)
                    memory.remove(oldest.file.nameWithoutExtension)
                    iterator.remove()
                }
            }
        }
    }

    @Synchronized fun size(): Long { initialize(); return total }

    @Synchronized fun clear() {
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        entries.clear(); evictionOrder.clear(); memory.clear()
        total = 0; invalidatedAt = 0; initialized = false
    }

    private fun initialize() {
        if (initialized) return
        directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { file ->
            val entry = Entry(file, file.length(), file.lastModified())
            entries[file.name] = entry; evictionOrder.add(entry); total += entry.bytes
        }
        invalidatedAt = runCatching { File(directory, "invalidated-at").readText(Charsets.UTF_8).toLong() }.getOrDefault(0L)
        initialized = true
    }

    private fun file(key: String): File {
        require(key.matches(cacheKey))
        return File(directory, "$key.json")
    }

    private companion object { val cacheKey = Regex("[a-f0-9]{64}") }
}
