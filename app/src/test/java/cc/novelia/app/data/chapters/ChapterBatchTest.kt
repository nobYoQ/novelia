package cc.novelia.app.data.chapters

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBatchTest {
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
