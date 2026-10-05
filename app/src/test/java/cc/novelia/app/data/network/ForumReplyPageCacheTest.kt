package cc.novelia.app.data.network

import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumPage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForumReplyPageCacheTest {
    private fun result(content: String, total: Long = 21) = ForumPage(total, listOf(ForumComment(
        12, postId = 5, rootId = 8, content = content, authorId = 42, authorUsername = "回复者", status = 0,
        createdAt = "2026-09-30T00:00:00Z", updatedAt = "2026-09-30T00:00:00Z")))

    private fun root(first: ForumPage<ForumComment>?) = result("根评论").items.single().copy(
        id = 8, rootId = null, replyCount = first?.total, replies = first)

    @Test fun embeddedFirstPageAvoidsAReadAndDoesNotReplaceNewerPageTotals() = runTest {
        val first = ForumPage(21, (12L..31L).map { result("首屏 $it").items.single().copy(id = it) })
        val cache = ForumReplyPageCache(this)
        try {
            assertTrue(cache.seedFirstPage(root(first)))
            assertEquals(first, cache.load(8, 0) { error("首屏不应再请求") })
            assertEquals("第二页", cache.load(8, 1) { result("第二页", 22) }.items.single().content)
            assertFalse(cache.seedFirstPage(root(first)))
            assertEquals(22L, cache.peek(8, 0)?.total)
        } finally { cache.close() }
    }

    @Test fun missingPartialAndMismatchedEmbeddedPagesUseTheIndependentEndpoint() = runTest {
        val cache = ForumReplyPageCache(this)
        try {
            val invalid = listOf(root(null), root(result("不完整首屏")), root(result("错误根 ID", 1).let { page ->
                page.copy(items = page.items.map { it.copy(rootId = 9) })
            }), root(result("错误帖子", 1).let { page -> page.copy(items = page.items.map { it.copy(postId = 6) }) }),
                root(ForumPage(2, List(2) { result("重复 ID").items.single() })), root(ForumPage(-1, emptyList())))
            invalid.forEach { assertFalse(cache.seedFirstPage(it)) }
            assertNull(cache.peek(8, 0))
            assertEquals("回退读取", cache.load(8, 0) { result("回退读取", 1) }.items.single().content)
        } finally { cache.close() }
    }

    @Test fun emptyEmbeddedPagesAreCachedAndCannotSurviveClosingTheScope() = runTest {
        val cache = ForumReplyPageCache(this)
        assertTrue(cache.seedFirstPage(root(ForumPage(0, emptyList()))))
        assertEquals(0L, cache.load(8, 0) { error("明确零回复不应请求") }.total)
        cache.close()
        assertNull(cache.peek(8, 0))
        assertFalse(cache.seedFirstPage(root(result("已失效的旧身份内容", 1))))
    }

    @Test fun lateEmbeddedSnapshotsCannotReplaceAnInFlightReplyRequest() = runTest {
        val cache = ForumReplyPageCache(this)
        val pending = CompletableDeferred<ForumPage<ForumComment>>()
        try {
            val request = async { cache.load(8, 0) { pending.await() } }
            runCurrent()
            assertFalse(cache.seedFirstPage(root(result("旧列表内容", 1))))
            pending.complete(result("最新回复", 1))
            assertEquals("最新回复", request.await().items.single().content)
        } finally { cache.close() }
    }

    @Test fun loadedPagesAreReusedAndSeparatedByThreadAndPage() = runTest {
        val cache = ForumReplyPageCache(this); var reads = 0
        try {
            val first = cache.load(8, 0) { reads++; result("第一页") }
            assertEquals(first, cache.peek(8, 0))
            assertEquals(first, cache.load(8, 0) { error("不应重复读取") })
            assertEquals("第二页", cache.load(8, 1) { reads++; result("第二页") }.items.single().content)
            assertEquals("另一串", cache.load(9, 0) { reads++; result("另一串") }.items.single().content)
            assertEquals(3, reads)
        } finally { cache.close() }
    }

    @Test fun concurrentWaitersShareOneRequest() = runTest {
        val cache = ForumReplyPageCache(this); val response = CompletableDeferred<ForumPage<ForumComment>>()
        var reads = 0
        try {
            val first = async { cache.load(8, 0) { reads++; response.await() } }
            val second = async { cache.load(8, 0) { error("已有同一页请求") } }
            runCurrent()
            assertEquals(1, reads)
            response.complete(result("共享响应"))
            assertEquals(first.await(), second.await())
        } finally { cache.close() }
    }

    @Test fun scrollingAwayCancelsOnlyTheWaiterAndReturningUsesTheCompletedPage() = runTest {
        val cache = ForumReplyPageCache(this); val response = CompletableDeferred<ForumPage<ForumComment>>()
        var reads = 0
        try {
            val visibleItem = async { cache.load(8, 0) { reads++; response.await() } }
            runCurrent()
            visibleItem.cancelAndJoin()
            response.complete(result("滚回后保留的正文"))
            runCurrent()
            assertNotNull(cache.peek(8, 0))
            assertEquals("滚回后保留的正文", cache.load(8, 0) { error("不应重新请求") }.items.single().content)
            assertEquals(1, reads)
        } finally { cache.close() }
    }

    @Test fun returningDuringAnUnfinishedRequestJoinsItInsteadOfRestarting() = runTest {
        val cache = ForumReplyPageCache(this); val response = CompletableDeferred<ForumPage<ForumComment>>()
        var reads = 0
        try {
            val first = async { cache.load(8, 0) { reads++; response.await() } }
            runCurrent(); first.cancelAndJoin()
            val returning = async { cache.load(8, 0) { error("不能因滚动重启请求") } }
            runCurrent()
            response.complete(result("继续原请求"))
            assertEquals("继续原请求", returning.await().items.single().content)
            assertEquals(1, reads)
        } finally { cache.close() }
    }

    @Test fun failedReadsCanRetryWithoutCancellingOtherThreads() = runTest {
        val cache = ForumReplyPageCache(this)
        try {
            assertTrue(runCatching { cache.load(8, 0) { throw IOException("连接中断") } }.exceptionOrNull() is IOException)
            assertNull(cache.peek(8, 0))
            assertEquals("其他串可读取", cache.load(9, 0) { result("其他串可读取") }.items.single().content)
            assertEquals("重试成功", cache.load(8, 0) { result("重试成功") }.items.single().content)
        } finally { cache.close() }
    }

    @Test fun closingForRefreshOrAccountChangesCancelsOldRequestsAndDropsTheirContent() = runTest {
        val previous = ForumReplyPageCache(this)
        previous.load(8, 0) { result("旧账号正文") }
        val pending = async {
            previous.load(8, 1) { withContext(NonCancellable) { delay(10); result("旧账号慢响应") } }
        }
        runCurrent(); previous.close()
        assertNull(previous.peek(8, 0))
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(previous.peek(8, 1))
        val current = ForumReplyPageCache(this)
        try {
            assertEquals("新身份正文", current.load(8, 0) { result("新身份正文") }.items.single().content)
        } finally { current.close() }
    }

    @Test fun perThreadLimitKeepsTheCurrentPageAndOtherThreadsWhileTotalsStayCurrent() = runTest {
        val cache = ForumReplyPageCache(this, pagesPerThread = 2)
        try {
            cache.load(8, 0) { result("零页", 40) }
            cache.load(9, 0) { result("其他串", 2) }
            cache.load(8, 1) { result("一页", 41) }
            assertEquals(41L, cache.peek(8, 0)?.total)
            cache.peek(8, 0) // 当前仍显示零页。
            cache.load(8, 2) { result("二页", 42) }
            assertNull(cache.peek(8, 1))
            assertNotNull(cache.peek(8, 0))
            assertEquals(42L, cache.peek(8, 0)?.total)
            assertEquals(2L, cache.peek(9, 0)?.total)
        } finally { cache.close() }
    }
}
