package cc.novelia.app.data.network

import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 页面内的回复缓存。每串保留最近三页；等待者被列表回收不会取消页面持有的请求。 */
internal class ForumReplyPageCache(owner: CoroutineScope, private val pagesPerThread: Int = 3) {
    init { require(pagesPerThread > 0) }
    private data class Key(val rootId: Long, val page: Int)
    private val job = SupervisorJob(owner.coroutineContext[Job])
    private val scope = CoroutineScope(owner.coroutineContext + job)
    private val pages = mutableMapOf<Long, LinkedHashMap<Int, ForumPage<ForumComment>>>()
    private val requests = mutableMapOf<Key, Deferred<ForumPage<ForumComment>>>()

    @Synchronized fun peek(rootId: Long, page: Int): ForumPage<ForumComment>? =
        if(job.isActive) pages[rootId]?.get(page) else null

    suspend fun load(rootId: Long, page: Int, loader: suspend () -> ForumPage<ForumComment>): ForumPage<ForumComment> {
        require(rootId > 0 && page >= 0)
        peek(rootId, page)?.let { return it }
        return request(Key(rootId, page), loader).await()
    }

    @Synchronized private fun request(key: Key, loader: suspend () -> ForumPage<ForumComment>): Deferred<ForumPage<ForumComment>> {
        job.ensureActive()
        requests[key]?.let { return it }
        val request = scope.async(start = CoroutineStart.LAZY) {
            // 可能在第一次查询缓存后、取得锁前，另一请求已经完成。
            peek(key.rootId, key.page)?.let { return@async it }
            val result = loader()
            require(result.total >= 0)
            currentCoroutineContext().ensureActive()
            synchronized(this@ForumReplyPageCache) {
                job.ensureActive()
                val thread = pages.getOrPut(key.rootId) { LinkedHashMap(4, 0.75f, true) }
                thread.replaceAll { _, cached -> cached.copy(total = result.total) }
                thread[key.page] = result
                while(thread.size > pagesPerThread) thread.remove(thread.keys.first())
            }
            result
        }
        requests[key] = request
        request.invokeOnCompletion {
            synchronized(this) { if(requests[key] === request) requests.remove(key) }
        }
        request.start()
        return request
    }

    fun close() {
        job.cancel()
        synchronized(this) { pages.clear(); requests.clear() }
    }
}
