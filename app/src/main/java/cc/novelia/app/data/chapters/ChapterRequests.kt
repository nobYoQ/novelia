package cc.novelia.app.data.chapters

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.network.SharedRequest
import cc.novelia.app.data.network.encodeSegment
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Account/login and cache generations are part of the identity, including forced refreshes. */
class ChapterRequests(private val store: LocalStore) {
    private data class Key(val api: NoveliaApi, val binding: SessionBinding, val generation: Long, val book: BookRef, val chapter: String)
    private val requests = SharedRequest<Key, Chapter>()

    fun invalidateBefore(generation: Long) { requests.cancelWhere { it.generation < generation } }

    suspend fun load(api: NoveliaApi, session: AuthenticationSession, binding: SessionBinding,
        generation: Long, ref: BookRef, id: String): Chapter {
        session.ensureCurrent(binding)
        if (generation != store.cacheGeneration.value) throw CancellationException("缓存已清理")
        val chapter = requests.await(Key(api, binding, generation, ref, id)) {
            session.ensureCurrent(binding)
            if (generation != store.cacheGeneration.value) throw CancellationException("缓存已清理")
            val fetched = appJson.decodeFromString<Chapter>(api.request("GET", "novel/${ref.key}/chapter/${encodeSegment(id)}", binding = binding))
            currentCoroutineContext().ensureActive()
            session.ensureCurrent(binding)
            // A cache write failure does not prevent foreground reading of a successful response.
            store.withCacheGeneration(generation) {
                runCatching { store.cacheChapter(ref, id, fetched); recordChapterFreshness(store, ref, id) }
            } ?: throw CancellationException("缓存已清理")
            fetched
        }
        currentCoroutineContext().ensureActive()
        session.ensureCurrent(binding)
        if (generation != store.cacheGeneration.value) throw CancellationException("缓存已清理")
        return chapter
    }
}
