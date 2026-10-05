package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.ForumComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 不等待全部计数完成才显示评论；失败保持未知，不能把请求失败解释为零条回复。 */
internal suspend fun loadForumReplyCounts(
    roots: List<ForumComment>, load: suspend (Long) -> Long, onCount: (Long, Long?) -> Unit
) = coroutineScope {
    val permits = Semaphore(4)
    roots.distinctBy { it.id }.map { root ->
        async {
            val count = if(root.replyCount > 0) root.replyCount else permits.withPermit {
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
