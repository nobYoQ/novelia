package cc.novelia.app

import cc.novelia.app.ui.ReaderChapterLoad
import cc.novelia.app.ui.ReaderChapterTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderChapterLoadTest {
    @Test fun failedChapterDoesNotNavigateAndRetryKeepsItsOriginalTarget() = runTest {
        var fail = true
        val opened = mutableListOf<String>()
        val loader = ReaderChapterLoad(this, { id -> if(fail) throw IOException("离线") else "正文:$id" },
            { target, body -> opened += "${target.id}:$body:${target.startAtEnd}" }, { it.message.orEmpty() }, StandardTestDispatcher(testScheduler))
        loader.request(ReaderChapterTarget("next", startAtEnd = true))
        assertTrue(loader.loading)
        runCurrent()
        assertFalse(loader.loading)
        assertEquals("离线", loader.error)
        assertEquals("next", loader.target?.id)
        assertTrue(opened.isEmpty())
        fail = false
        loader.retry()
        assertNull(loader.error)
        runCurrent()
        assertEquals(listOf("next:正文:next:true"), opened)
        assertNull(loader.target)
        assertFalse(loader.loading)
    }

    @Test fun duplicatePullsUseOneRequestAndCancelledResultsCannotNavigate() = runTest {
        var requests = 0
        val response = CompletableDeferred<String>()
        val opened = mutableListOf<String>()
        val loader = ReaderChapterLoad(this, { requests++; withContext(NonCancellable) { response.await() } },
            { target, _ -> opened += target.id }, { "失败" }, StandardTestDispatcher(testScheduler))
        loader.request(ReaderChapterTarget("next"))
        runCurrent()
        loader.request(ReaderChapterTarget("next"))
        runCurrent()
        assertEquals(1, requests)
        loader.cancel()
        response.complete("迟到正文")
        runCurrent()
        assertTrue(opened.isEmpty())
        assertFalse(loader.loading)
        assertNull(loader.error)
        assertNull(loader.target)
    }

    @Test fun choosingAnotherChapterInvalidatesLateFailureAndOldFinallyBlock() = runTest {
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        val opened = mutableListOf<String>()
        val loader = ReaderChapterLoad(this, { id -> withContext(NonCancellable) { if(id == "old") first.await() else second.await() } },
            { target, _ -> opened += target.id }, { it.message.orEmpty() }, StandardTestDispatcher(testScheduler))
        loader.request(ReaderChapterTarget("old"))
        runCurrent()
        loader.request(ReaderChapterTarget("new"))
        runCurrent()
        first.completeExceptionally(IOException("旧请求失败"))
        runCurrent()
        assertTrue(loader.loading)
        assertNull(loader.error)
        assertEquals("new", loader.target?.id)
        second.complete("新正文")
        runCurrent()
        assertEquals(listOf("new"), opened)
        assertNull(loader.target)
    }

    @Test fun dismissingAnErrorClearsRetryWithoutOpeningAChapter() = runTest {
        var opens = 0
        val loader = ReaderChapterLoad<String>(this, { throw IOException("失败") }, { _, _ -> opens++ },
            { it.message.orEmpty() }, StandardTestDispatcher(testScheduler))
        loader.request(ReaderChapterTarget("next"))
        runCurrent()
        loader.cancel()
        loader.retry()
        runCurrent()
        assertEquals(0, opens)
        assertNull(loader.error)
        assertNull(loader.target)
        assertFalse(loader.loading)
    }
}
