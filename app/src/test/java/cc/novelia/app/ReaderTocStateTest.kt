package cc.novelia.app

import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.reader.ReaderTocState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTocStateTest {
    private val chapters = listOf(TocItem("分卷"), TocItem("第一章", chapterId = "first"), TocItem("第二章", chapterId = "second"))

    @Test fun concurrentReadersAndChapterChangesReuseTheSameDirectory() = runTest {
        val response = CompletableDeferred<List<TocItem>>()
        val requests = mutableListOf<Boolean>()
        val state = ReaderTocState { force -> requests += force; response.await() }
        launch { state.ensureLoaded("first", 2) }
        launch { state.ensureLoaded("first", 2) }
        runCurrent()
        assertEquals(listOf(false), requests)
        assertTrue(state.loading)
        response.complete(chapters)
        runCurrent()
        repeat(3) {
            state.ensureLoaded("second", 2)
            state.ensureLoaded("first", 2)
        }
        assertEquals(listOf(false), requests)
        assertSame(chapters, state.toc)
        assertFalse(state.loading)
    }

    @Test fun staleDiskDirectoryIsExtendedOnlyOnceAndSectionHeadingsDoNotCountAsChapters() = runTest {
        val requests = mutableListOf<Boolean>()
        val state = ReaderTocState { force -> requests += force; if(force) chapters else chapters.dropLast(1) }
        state.ensureLoaded("first", 2)
        assertEquals(listOf(false, true), requests)
        assertEquals(chapters, state.toc)
        state.ensureLoaded("second", 2)
        assertEquals(2, requests.size)
    }

    @Test fun missingChapterAndNewKnownCountRefreshWithoutClearingTheExistingList() = runTest {
        val requests = mutableListOf<Boolean>()
        val response = CompletableDeferred<List<TocItem>>()
        val state = ReaderTocState { force -> requests += force; if(force) response.await() else chapters }
        state.ensureLoaded("first", 2)
        launch { state.ensureLoaded("third", 3) }
        runCurrent()
        assertTrue(state.loading)
        assertSame(chapters, state.toc)
        val updated = chapters + TocItem("第三章", chapterId = "third")
        response.complete(updated)
        runCurrent()
        assertEquals(listOf(false, true), requests)
        assertEquals(updated, state.toc)
        state.ensureLoaded("third", 3)
        assertEquals(2, requests.size)
    }

    @Test fun unchangedServerGapDoesNotReloadOnEveryChapterButExplicitRefreshRetries() = runTest {
        val requests = mutableListOf<Boolean>()
        val state = ReaderTocState { force -> requests += force; chapters }
        state.ensureLoaded("first", 3)
        repeat(3) {
            state.ensureLoaded("second", 3)
            state.ensureLoaded("first", 3)
        }
        assertEquals(listOf(false, true), requests)
        state.ensureLoaded("second", 3, refresh = true)
        assertEquals(listOf(false, true, true), requests)
    }

    @Test fun failedRefreshKeepsDirectoryAndCanBeRetried() = runTest {
        var fail = false
        val state = ReaderTocState { if(fail) throw IOException("离线"); chapters }
        state.ensureLoaded("first", 2)
        fail = true
        state.ensureLoaded("first", 2, refresh = true)
        assertSame(chapters, state.toc)
        assertEquals("离线", state.error?.message)
        assertFalse(state.loading)
        fail = false
        state.ensureLoaded("first", 2, refresh = true)
        assertNull(state.error)
        assertSame(chapters, state.toc)
    }

    @Test fun failedInitialLoadWaitsForExplicitRetry() = runTest {
        var requests = 0
        val state = ReaderTocState { if(++requests == 1) throw IOException("离线"); chapters }
        state.ensureLoaded("first", 2)
        assertNull(state.toc)
        assertNotNull(state.error)
        state.ensureLoaded("first", 2)
        assertEquals(1, requests)
        state.ensureLoaded("first", 2, refresh = true)
        assertEquals(2, requests)
        assertNull(state.error)
        assertSame(chapters, state.toc)
    }

    @Test fun cancelledLoadCannotPublishLateResultsAndNextReaderCanLoad() = runTest {
        val response = CompletableDeferred<List<TocItem>>()
        var requests = 0
        val state = ReaderTocState {
            requests++
            withContext(NonCancellable) { response.await() }
        }
        val first = launch { state.ensureLoaded("first", 2) }
        runCurrent()
        first.cancel()
        response.complete(chapters)
        runCurrent()
        assertNull(state.toc)
        assertNull(state.error)
        assertFalse(state.loading)
        state.ensureLoaded("second", 2)
        assertEquals(2, requests)
        assertSame(chapters, state.toc)
    }
}
