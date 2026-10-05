package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.ForumComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ForumReplyCountsTest {
    private fun root(id: Long, count: Long = 0) = ForumComment(id, content = "根评论", authorId = 1,
        authorUsername = "读者", status = 0, createdAt = "2026-09-30T00:00:00Z", updatedAt = "2026-09-30T00:00:00Z", replyCount = count)

    @Test fun fillsMissingCountsWithoutConfusingFailuresAndEmptyThreads() = runTest {
        val counts = mutableMapOf<Long, Long?>(); val requested = mutableListOf<Long>()
        loadForumReplyCounts(listOf(root(1, 23), root(2), root(3), root(4), root(2)), { id ->
            requested += id
            when(id) { 2L -> 7L; 3L -> 0L; else -> throw IOException("断网") }
        }) { id, count -> counts[id] = count }
        assertEquals(mapOf(1L to 23L, 2L to 7L, 3L to 0L, 4L to null), counts)
        assertEquals(listOf(2L, 3L, 4L), requested)
    }

    @Test fun countReadsAreBoundedAndPublishAsEachRequestCompletes() = runTest {
        var active = 0; var maximum = 0; val completed = mutableListOf<Long>()
        loadForumReplyCounts((1L..20L).map { root(it) }, { id ->
            active++; maximum = maxOf(active, maximum)
            delay(if(id == 1L) 100 else 10)
            active--; id
        }) { id, _ -> completed += id }
        assertEquals(4, maximum)
        assertEquals(20, completed.size)
        assertNotEquals(1L, completed.first())
    }

    @Test fun cancellationAndAccountChangesDoNotPublishFakeZeroCounts() = runTest {
        for(error in listOf(CancellationException("取消"), SessionChangedException())) {
            val published = mutableMapOf<Long, Long?>()
            val failure = runCatching {
                loadForumReplyCounts(listOf(root(1)), { throw error }) { id, count -> published[id] = count }
            }.exceptionOrNull()
            // 协程调试的堆栈恢复可能复制异常；校验对调用方有意义的类型与原因。
            if(error is CancellationException) assertTrue(failure is CancellationException)
            else assertTrue(failure is SessionChangedException)
            assertEquals(error.message, failure?.message)
            assertTrue(published.isEmpty())
        }
    }
}
