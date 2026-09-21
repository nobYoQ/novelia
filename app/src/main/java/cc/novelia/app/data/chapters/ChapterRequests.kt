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

/**
 * 章节网络请求的共享与落盘边界。身份包含 API 实例、账号/登录代次、缓存代次和书章标识，
 * 防止不同账号或清理缓存前后的请求相互复用；强制刷新也可以共享同身份的在途请求。
 * 读取磁盘缓存的优先级由调用方决定，本类负责真正发起网络读取。
 */
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
        // 共享任务完成后，每个订阅者还需重新检查自己的取消状态和当前环境。
        currentCoroutineContext().ensureActive()
        session.ensureCurrent(binding)
        if (generation != store.cacheGeneration.value) throw CancellationException("缓存已清理")
        return chapter
    }
}
