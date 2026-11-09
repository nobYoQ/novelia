package cc.novelia.app.files.comic

import cc.novelia.app.data.storage.hashName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Cache entries are replaceable; never evict from the permanent originals or cover directory. */
internal class ComicPageCache(private val directory: File, private val source: File, private val format: String,
    private val revision: String) {
    fun load(name: String): File {
        val key = hashName(revision + "/" + name)
        val file = File(directory, key)
        synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
            if (file.isFile) { file.setLastModified(System.currentTimeMillis()); return file }
            directory.mkdirs()
            val tmp = File.createTempFile("page-", ".tmp", directory)
            try {
                tmp.outputStream().use { out -> extractComicPage(source, format, name, out) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("读取已取消")
                } }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                synchronized(trimLock) {
                    val entries = directory.listFiles().orEmpty().filter { it.name.length == 64 }
                    var bytes = entries.sumOf { it.length() }
                    if (bytes > budget) for (old in entries.sortedBy { it.lastModified() }) {
                        if (old == file) continue
                        val size = old.length()
                        if (old.delete()) bytes -= size
                        if (bytes <= budget) break
                    }
                }
            } finally { tmp.delete() }
            return file
        }
    }
    companion object {
        private val locks = Array(16) { Any() }
        private val trimLock = Any()
        private const val budget = 256L * 1024 * 1024
    }
}
