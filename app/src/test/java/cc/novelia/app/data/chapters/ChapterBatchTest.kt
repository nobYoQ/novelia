package cc.novelia.app.data.chapters

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.cache.ChapterCacheGeneration
import cc.novelia.app.data.model.BookRef
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBatchTest {
    @Test fun clearedCacheGenerationCancelsWholeBatch() = runTest {
        val result = runCatching {
            cacheChapterBatch(listOf("a", "b", "c"), load = { throw CancellationException("缓存已清理") }) { _, _ ->
                fail("已失效的章节不能完成")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun backgroundBatchesShareSlotsAcrossBooksAndCanBeCancelledIndependently() = runTest {
        val slots = Semaphore(MAX_CONCURRENT_CHAPTERS)
        val ids = (1..8).map(Int::toString)
        fun request(book: String) = ChapterCacheRequest(ChapterCacheKey(BookRef("syosetu", book), SessionBinding(null, 1)),
            ChapterCacheGeneration(1, 1), 1, ids, false)
        val first = request("first")
        val second = request("second")
        var peak = 0
        var running = 0
        val began = mutableListOf<String>()
        val queue = ChapterCacheQueue(this) { batch, progress ->
            began += batch.key.book.id
            cacheChapterBatch(batch.ids, slots, load = {
                peak = maxOf(peak, ++running)
                try { delay(10) } finally { running-- }
            }, progress = progress)
        }
        queue.start(first)
        queue.start(first)
        queue.start(second)
        testScheduler.advanceTimeBy(25)
        assertEquals(3, peak)
        assertEquals(listOf("first", "second"), began)
        assertTrue(queue.progress.value.getValue(second.key).completed > 0)
        queue.cancel(first.key)
        testScheduler.advanceUntilIdle()
        val results = queue.progress.value
        assertTrue(results.getValue(first.key).completed < ids.size)
        assertTrue(results.getValue(first.key).result!!.contains("取消"))
        assertEquals(ids.size, results.getValue(second.key).completed)
        assertTrue(results.getValue(second.key).result!!.contains("完成"))
    }

    @Test fun parallelBatchIsBoundedDeduplicatedAndReportsMonotonicProgress() = runTest {
        var active = 0
        var peak = 0
        val seen = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val start = testScheduler.currentTime
        cacheChapterBatch((1..12).map(Int::toString) + listOf("1", "2"), load = {
            active++
            peak = maxOf(peak, active)
            seen += it
            delay(100)
            active--
        }) { done, total ->
            assertEquals(12, total)
            progress += done
            // 即使界面更新会挂起，回调也不得重叠或乱序。
            delay(1)
        }
        assertEquals(3, peak)
        assertEquals(12, seen.distinct().size)
        assertEquals(12, seen.size)
        assertEquals((1..12).toList(), progress)
        assertTrue(testScheduler.currentTime - start < 500)
    }

    @Test fun failureCancelsOtherWorkersWithoutStartingTheRestOfTheBatch() = runTest {
        val started = mutableListOf<String>()
        val allStarted = CompletableDeferred<Unit>()
        var finished = 0
        val error = runCatching {
            cacheChapterBatch((1..20).map(Int::toString), load = { id ->
                started += id
                if(started.size == 3) allStarted.complete(Unit)
                try {
                    allStarted.await()
                    if(id == "1") throw IOException("断网")
                    awaitCancellation()
                } finally { finished++ }
            }) { _, _ -> fail("失败的章节不能计入完成进度") }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(3, started.size)
        assertEquals(3, finished)
    }

    @Test fun cancellingBatchStopsAllLoadsAndDoesNotReportCompletion() = runTest {
        var cancelled = 0
        val job = launch {
            cacheChapterBatch((1..20).map(Int::toString), load = {
                try { awaitCancellation() } finally { cancelled++ }
            }) { _, _ -> fail("取消的任务不能报告完成") }
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(3, cancelled)
    }
}
