package cc.novelia.app.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudSyncRuntimeTest {
    private val action = PendingAction("running", "alice", "PUT", "user/read-history/syosetu/one")

    @Test fun onlineSubmissionIsVisibleOnlyWhileTheRequestRuns() = runTest {
        val queue = CloudMutationQueue()
        var pending = emptyList<PendingAction>()
        val response = CompletableDeferred<Unit>()
        val request = launch { queue.submit(action, { pending = it(pending) }) { response.await() } }
        runCurrent()
        assertEquals(mapOf(action.id to action), queue.inFlight.value)
        assertEquals(listOf(action), pending)
        response.complete(Unit)
        request.join()
        assertTrue(queue.inFlight.value.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun cancelledReplayClearsRuntimeStateButKeepsTheIntentForRetry() = runTest {
        val queue = CloudMutationQueue()
        var pending = listOf(action)
        val request = launch { queue.replayEligible("alice", { pending }, { pending = it(pending) }) { CompletableDeferred<Unit>().await() } }
        runCurrent()
        assertTrue(action.id in queue.inFlight.value)
        request.cancelAndJoin()
        assertTrue(queue.inFlight.value.isEmpty())
        assertEquals(listOf(action), pending)
    }

    @Test fun offlineFailureCanBePersistedAndSuccessfulRetryClearsLiveState() = runTest {
        val queue = CloudMutationQueue()
        var pending = emptyList<PendingAction>()
        var recorded: IOException? = null
        val offline = IOException("offline fixture")
        assertTrue(queue.submit(action, { pending = it(pending) }, onQueuedFailure = { recorded = it }) { throw offline })
        assertSame(offline, recorded)
        assertTrue(queue.inFlight.value.isEmpty())
        val result = queue.replayEligible("alice", { pending }, { pending = it(pending) }) {
            assertEquals(action, queue.inFlight.value[action.id])
        }
        assertEquals(1, result.completed)
        assertTrue(pending.isEmpty())
        assertTrue(queue.inFlight.value.isEmpty())
    }
}
