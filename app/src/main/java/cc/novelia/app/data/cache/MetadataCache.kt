package cc.novelia.app.data.cache

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 有容量上限的元数据响应缓存，调用方生成包含账号身份的哈希键。
 * 文件修改时间表示获取响应的时间，读取命中不会延长有效期；淘汰也按获取时间排序。
 * 与正文缓存的访问时间不同，此时间参与写操作后的失效判断，不能随访问更新。
 */
class MetadataCache(private val directory: File, private val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxBytes > 0) }
    private data class Entry(val file: File, val bytes: Long, val fetchedAt: Long)
    private val entries = mutableMapOf<String, Entry>()
    private var initialized = false
    private var total = 0L
    private var invalidatedAt = 0L

    /** 仅返回未过期且晚于本地/调用方失效时间的内容；时钟回拨形成的未来缓存也视为未命中。 */
    @Synchronized fun read(key: String, now: Long = System.currentTimeMillis(), maxAgeMillis: Long = Long.MAX_VALUE, newerThan: Long = 0): String? {
        val file = file(key)
        initialize()
        val age = now - file.lastModified()
        if (!file.isFile || age < 0 || age > maxAgeMillis || file.lastModified() <= maxOf(newerThan, invalidatedAt)) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
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
        entries.remove(destination.name)?.let { total -= it.bytes }
        entries[destination.name] = Entry(destination, bytes.size.toLong(), fetchedAt)
        total += bytes.size
        if (total > maxBytes) {
            for (entry in entries.values.sortedBy { it.fetchedAt }) {
                if (entry.file.delete() || !entry.file.exists()) {
                    total -= entry.bytes
                    entries.remove(entry.file.name)
                }
                if (total <= maxBytes) break
            }
        }
    }

    @Synchronized fun size(): Long { initialize(); return total }

    @Synchronized fun clear() {
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        entries.clear(); total = 0; invalidatedAt = 0; initialized = false
    }

    private fun initialize() {
        if (initialized) return
        directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { file ->
            val entry = Entry(file, file.length(), file.lastModified())
            entries[file.name] = entry; total += entry.bytes
        }
        invalidatedAt = runCatching { File(directory, "invalidated-at").readText(Charsets.UTF_8).toLong() }.getOrDefault(0L)
        initialized = true
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.json")
    }
}
