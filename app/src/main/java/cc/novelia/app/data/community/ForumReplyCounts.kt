package cc.novelia.app.data.community

import cc.novelia.app.data.auth.SessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 优先使用服务端计数（含零）；仅补齐旧响应缺失的计数，失败保持未知。 */
internal suspend fun loadForumReplyCounts(
    roots: List<ForumComment>, load: suspend (Long) -> Long, onCount: (Long, Long?) -> Unit
) = coroutineScope {
    val permits = Semaphore(4)
    roots.distinctBy { it.id }.map { root ->
        async {
            val count = root.replyCount?.takeIf { it >= 0 } ?: permits.withPermit {
                try { load(root.id).also { require(it >= 0) } }
                catch(error: CancellationException) { throw error }
                catch(error: SessionChangedException) { throw error }
                catch(_: Exception) { null }
            }
            onCount(root.id, count)
        }
    }.awaitAll()
    Unit
}
