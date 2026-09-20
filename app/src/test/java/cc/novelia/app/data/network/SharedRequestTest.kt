package cc.novelia.app.data.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedRequestTest {
    @Test(timeout = 10_000) fun foregroundAndPrefetchShareRequestAndOneCancellationDoesNotCancelOther() = runBlocking {
        val requests = SharedRequest<String, String>()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.await("alice:1:0:chapter") {
            calls.incrementAndGet(); started.complete(Unit); finish.await(); "正文"
        } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.await("alice:1:0:chapter") { error("duplicate request") } }
        first.cancelAndJoin()
        finish.complete(Unit)
        assertEquals("正文", second.await())
        assertEquals(1, calls.get())
    }

    @Test(timeout = 10_000) fun lastCancellationStopsIOAndNewGenerationNeverJoinsIt() = runBlocking {
        val requests = SharedRequest<String, String>()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) { requests.await("alice:1:0:chapter") {
            try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        started.await()
        assertEquals("新账号", requests.await("bob:2:0:chapter") { "新账号" })
        assertEquals("新缓存", requests.await("alice:1:1:chapter") { "新缓存" })
        old.cancelAndJoin()
        stopped.await()
        assertEquals("重试", requests.await("alice:1:0:chapter") { "重试" })
    }

    @Test(timeout = 10_000) fun cacheInvalidationCancelsOldGenerationsWithoutCancellingNewReaders() = runBlocking {
        val requests = SharedRequest<Int, String>()
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = async { requests.await(0) { oldStarted.complete(Unit); awaitCancellation() } }
        oldStarted.await()
        val fresh = async { requests.await(1) { newStarted.complete(Unit); finish.await(); "fresh" } }
        newStarted.await()
        requests.cancelWhere { it < 1 }
        old.join()
        assertTrue(old.isCancelled)
        finish.complete(Unit)
        assertEquals("fresh", fresh.await())
    }
}
