package cc.novelia.app.data.library

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 两个历史来源共用详情缓存；游客也能补全公开目录，不将历史条目加入书架。 */
internal class HistoryCatalogLoader(
    private val session: AuthenticationSession,
    private val cache: MetadataCache,
    private val mutationAt: (String) -> Long,
    private val fetch: suspend (BookRef) -> WebDetail,
) {
    private val requests = Semaphore(2)
    private data class CachedCatalog(val book: BookCard, val fresh: Boolean)

    suspend fun load(book: BookCard, binding: SessionBinding, publish: (BookCard) -> Unit) {
        if (book.ref.isLocal || book.ref.isWenku) return
        session.ensureCurrent(binding)
        val path = "novel/${book.ref.key}"
        val cached = withContext(Dispatchers.IO) {
            val snapshot = cache.readSnapshot(hashName("${binding.cacheAccount}:$path"), newerThan = mutationAt(path))
                ?: return@withContext null
            val detail = runCatching { appJson.decodeFromString<WebDetail>(snapshot.text) }.getOrNull()
                ?: return@withContext null
            val behind = detail.catalogBehind(book)
            val known = detail.enrichBook(book, binding.account, if (behind) null else detail.latestReadableChapter())
            val reusable = known.latestChapter != null && !known.novelType.isNullOrBlank() &&
                System.currentTimeMillis() - snapshot.fetchedAt in 0 until 5 * 60_000L
            CachedCatalog(known, reusable)
        }
        if (cached != null) {
            session.ensureCurrent(binding)
            publish(cached.book)
            if (cached.fresh) return
        }
        requests.withPermit {
            session.ensureCurrent(binding)
            val current = fetch(book.ref)
            val resolved = withContext(Dispatchers.Default) {
                current.enrichBook(book, binding.account, current.latestReadableChapter())
            }
            session.ensureCurrent(binding)
            publish(resolved)
        }
    }
}
