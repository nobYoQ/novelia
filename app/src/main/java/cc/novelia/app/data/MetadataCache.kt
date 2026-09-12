package cc.novelia.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small, account-keyed response cache. Modification time is freshness, not access time. */
class MetadataCache(private val directory: File, private val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxBytes > 0) }

    @Synchronized fun read(key: String, now: Long = System.currentTimeMillis(), maxAgeMillis: Long = Long.MAX_VALUE, newerThan: Long = 0): String? {
        val file = file(key)
        val age = now - file.lastModified()
        val invalidatedAt = runCatching { File(directory, "invalidated-at").readText(Charsets.UTF_8).toLong() }.getOrDefault(0L)
        if (!file.isFile || age < 0 || age > maxAgeMillis || file.lastModified() <= maxOf(newerThan, invalidatedAt)) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** Keep mutation invalidation across process restarts without rewriting cached responses. */
    @Synchronized fun invalidate(timestamp: Long) {
        directory.mkdirs()
        val pending = File.createTempFile("invalidate-", ".tmp", directory)
        try {
            pending.writeText(timestamp.toString(), Charsets.UTF_8)
            Files.move(pending.toPath(), File(directory, "invalidated-at").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
    }

    @Synchronized fun write(key: String, text: String, fetchedAt: Long = System.currentTimeMillis()) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return
        directory.mkdirs()
        val destination = file(key)
        val pending = File.createTempFile("metadata-", ".tmp", directory)
        try {
            pending.writeBytes(bytes)
            check(pending.setLastModified(fetchedAt)) { "无法记录缓存时间" }
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        val entries = directory.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
        var total = entries.sumOf { it.length() }
        if (total > maxBytes) {
            for (entry in entries.sortedBy { it.lastModified() }) {
                val length = entry.length()
                if (entry.delete()) total -= length
                if (total <= maxBytes) break
            }
        }
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$key.json")
    }
}
