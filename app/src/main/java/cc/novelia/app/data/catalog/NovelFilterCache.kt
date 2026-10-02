package cc.novelia.app.data.catalog

import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.model.Author
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 筛选只解码这些小字段，跳过可能包含数千章的目录，也不产生阅读更新通知。 */
@Serializable internal data class NovelFilterDetails(
    val totalCharacters: Long? = null,
    val authors: List<Author> = emptyList(),
) {
    fun applyTo(book: BookCard) = book.copy(
        totalCharacters = totalCharacters?.takeIf { it >= 0 } ?: book.totalCharacters,
        authors = authors.map { it.name }.ifEmpty { book.authors },
    )
}

@Serializable internal data class NovelFilterSource(val chapters: Int, val updatedAt: Long?, val type: String?) {
    companion object { fun from(book: BookCard) = NovelFilterSource(book.total, book.updateAt, book.novelType) }
}

@Serializable internal data class NovelFilterMetadata(val source: NovelFilterSource, val details: NovelFilterDetails)

/** 与普通详情共用账号隔离、磁盘容量和清理机制，字数独立于完整目录保存。 */
internal class NovelFilterMetadataCache(private val cache: MetadataCache) {
    data class Snapshot(val metadata: NovelFilterMetadata, val fetchedAt: Long) {
        fun matchesSource(book: BookCard) = metadata.source == NovelFilterSource.from(book)
        fun reusable(book: BookCard, now: Long = System.currentTimeMillis()): Boolean {
            val ageLimit = if(metadata.details.totalCharacters?.let { it >= 0 } == true) 24 * 60 * 60_000L else 15 * 60_000L
            return matchesSource(book) && now - fetchedAt in 0..ageLimit
        }
    }

    fun read(ref: BookRef, account: String, newerThan: Long, now: Long = System.currentTimeMillis()): Snapshot? =
        cache.readSnapshot(key(ref, account), now, maxAgeMillis = 24 * 60 * 60_000L, newerThan = newerThan)?.let { snapshot ->
            runCatching { Snapshot(appJson.decodeFromString<NovelFilterMetadata>(snapshot.text), snapshot.fetchedAt) }.getOrNull()
        }

    fun write(book: BookCard, account: String, details: NovelFilterDetails, fetchedAt: Long) {
        cache.write(key(book.ref, account), appJson.encodeToString(NovelFilterMetadata(NovelFilterSource.from(book), details)), fetchedAt)
    }

    private fun key(ref: BookRef, account: String) = hashName("$account:novel-filter/${ref.key}")
}

/** 只改变本地范围时复用同一远端候选页；身份键由控制器包含会话、缓存代次及远端查询。 */
internal class NovelFilterPageCache(private val capacity: Int = 12, private val lifetimeMillis: Long = 2 * 60_000L) {
    private data class Entry(val page: Page<BookCard>, val fetchedAt: Long)
    private val entries = LinkedHashMap<Any, Entry>(capacity, .75f, true)
    @Synchronized fun get(key: Any, now: Long = System.currentTimeMillis()): Page<BookCard>? =
        entries[key]?.takeIf { now - it.fetchedAt in 0..lifetimeMillis }?.page
    @Synchronized fun put(key: Any, page: Page<BookCard>, fetchedAt: Long = System.currentTimeMillis()) {
        if((entries[key]?.fetchedAt ?: Long.MIN_VALUE) > fetchedAt) return
        entries[key] = Entry(page, fetchedAt)
        while(entries.size > capacity) entries.remove(entries.keys.first())
    }
}
