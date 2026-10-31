package cc.novelia.app.data.cache

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class ChapterCacheSummary(val ref: BookRef?, val title: String, val chapters: Int, val bytes: Long)

/** 与正文分开保存归属；旧版本的哈希文件只能通过已知书章标识精确匹配，不能按书名猜测。 */
internal class ChapterCacheCatalog(private val directory: File, private val indexFile: File) {
    @Serializable private data class Owner(val ref: BookRef, val title: String)
    private var owners: MutableMap<String, Owner>? = null
    private val fileName = Regex("[a-f0-9]{64}\\.json")

    private fun entries(): MutableMap<String, Owner> = owners ?: runCatching {
        appJson.decodeFromString<Map<String, Owner>>(indexFile.readText(Charsets.UTF_8))
            .filterKeys { fileName.matches(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf()).also { owners = it }

    fun register(ref: BookRef, title: String, ids: Collection<String>) {
        if(ref.isLocal || ref.isWenku) return
        val entries = entries()
        val owner = Owner(ref, title.ifBlank { entries.values.firstOrNull { it.ref == ref }?.title ?: ref.key })
        val names = ids.map { hashName("${ref.key}/$it") + ".json" }.filter { entries[it] != owner }
        if(names.isEmpty()) return
        val alive = if(names.size == 1) names.filter { File(directory, it).isFile }.toSet()
            else directory.listFiles().orEmpty().filter { it.isFile }.mapTo(hashSetOf()) { it.name }
        val changed = names.filter { it in alive }
        changed.forEach { entries[it] = owner }
        if(changed.isNotEmpty()) save()
    }

    fun recover(books: Map<BookRef, String>, ids: Collection<String>) {
        val unknown = files(null).mapTo(hashSetOf()) { it.name }
        if(unknown.isEmpty()) return
        val entries = entries()
        var changed = false
        for((ref, title) in books) {
            if(ref.isLocal || ref.isWenku) continue
            for(id in ids) {
                val name = hashName("${ref.key}/$id") + ".json"
                if(unknown.remove(name)) {
                    entries[name] = Owner(ref, title.ifBlank { ref.key })
                    changed = true
                }
            }
            if(unknown.isEmpty()) break
        }
        if(changed) save()
    }

    fun files(ref: BookRef?): List<File> {
        val entries = entries()
        return directory.listFiles().orEmpty().filter {
            it.isFile && fileName.matches(it.name) && entries[it.name]?.ref == ref
        }
    }

    fun summaries(): List<ChapterCacheSummary> {
        val entries = entries()
        return directory.listFiles().orEmpty().filter { it.isFile && fileName.matches(it.name) }
            .groupBy { entries[it.name]?.ref }.map { (ref, files) ->
                ChapterCacheSummary(ref, files.firstNotNullOfOrNull { entries[it.name]?.title }
                    ?: "未识别的旧版章节缓存", files.size, files.sumOf { it.length() })
            }.sortedWith(compareByDescending<ChapterCacheSummary> { it.bytes }.thenBy { it.title })
    }

    fun prune() {
        if(entries().keys.removeAll { !File(directory, it).isFile }) save()
    }

    fun clear() {
        check(!indexFile.exists() || indexFile.delete()) { "无法清理章节缓存索引" }
        owners = mutableMapOf()
    }

    private fun save() {
        val entries = entries()
        entries.keys.removeAll { !File(directory, it).isFile }
        val pending = File.createTempFile("chapter-index-", ".tmp", indexFile.parentFile)
        try {
            pending.writeText(appJson.encodeToString(entries.toMap()), Charsets.UTF_8)
            Files.move(pending.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
    }
}

data class ChapterCacheGeneration(val all: Long, val book: Long)
