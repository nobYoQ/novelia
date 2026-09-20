package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.auth.SessionState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Profile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CloudMutationQueueTest {
    private class PendingStore(var pending: List<PendingAction> = emptyList()) {
        fun update(transform: (List<PendingAction>) -> List<PendingAction>) { pending = transform(pending) }
    }
    private fun action(id: String, path: String = "user/read-history/syosetu/1") = PendingAction(id, "alice", "PUT", path, id)

    @Test fun newerOnlineSuccessRemovesOldOfflineWriteBeforeReplay() = runTest {
        val queue = CloudMutationQueue()
        val store = PendingStore()
        assertTrue(queue.submit(action("chapter-5"), store::update) { throw IOException("offline") })
        assertFalse(queue.submit(action("chapter-6"), store::update) {})
        var replayed = false
        queue.replay("alice", { store.pending }, store::update) { replayed = true }
        assertFalse(replayed)
        assertTrue(store.pending.isEmpty())
    }

    @Test fun newWriteWaitsForInFlightReplayAndRemainsNewest() = runTest {
        val queue = CloudMutationQueue()
        val store = PendingStore(listOf(action("chapter-5")))
        val started = CompletableDeferred<Unit>()
        val finishReplay = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val replay = launch {
            queue.replay("alice", { store.pending }, store::update) {
                writes += it.id
                started.complete(Unit)
                finishReplay.await()
            }
        }
        started.await()
        val online = launch { queue.submit(action("chapter-6"), store::update) { writes += it.id } }
        yield()
        assertEquals(listOf("chapter-5"), writes)
        finishReplay.complete(Unit)
        replay.join(); online.join()
        assertEquals(listOf("chapter-5", "chapter-6"), writes)
        assertTrue(store.pending.isEmpty())
    }

    @Test fun replaySkipsCapturedItemAlreadySupersededByOnlineWrite() = runTest {
        val queue = CloudMutationQueue()
        val first = action("first", "user/read-history/syosetu/2")
        val store = PendingStore(listOf(first, action("chapter-5")))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val replayed = mutableListOf<String>()
        val replay = launch {
            queue.replay("alice", { store.pending }, store::update) {
                replayed += it.id
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        queue.submit(action("chapter-6"), store::update) {}
        finish.complete(Unit)
        replay.join()
        assertEquals(listOf("first"), replayed)
        assertTrue(store.pending.isEmpty())
    }

    @Test fun folderMovesSupersedeOlderFavoriteOperationsButKeepOtherAccounts() = runTest {
        val old = action("old", "user/favored-web/old-folder/syosetu/1")
        val other = old.copy(id = "bob", account = "bob")
        val store = PendingStore(listOf(old, other))
        CloudMutationQueue().submit(action("new", "user/favored-web/new-folder/syosetu/1"), store::update) {}
        assertEquals(listOf(other), store.pending)
    }

    @Test fun accountSwitchDuringReplayStopsBeforeNextWriteAndRetainsIt() = runTest {
        val state = SessionState("old", Profile("alice", "member", 0, Long.MAX_VALUE))
        val binding = state.capture()
        val first = action("first", "user/read-history/syosetu/2")
        val second = action("second")
        val store = PendingStore(listOf(first, second))
        val writes = mutableListOf<String>()
        val error = runCatching {
            CloudMutationQueue().replay("alice", { store.pending }, store::update) {
                state.tokenFor(binding)
                writes += it.id
                state.clear()
            }
        }.exceptionOrNull()
        assertTrue(error is SessionChangedException)
        assertEquals(listOf("first"), writes)
        assertEquals(listOf(second), store.pending)
    }

    @Test fun authenticationFailureIsNeverQueuedAsAnOfflineWrite() = runTest {
        val store = PendingStore()
        val error = runCatching { CloudMutationQueue().submit(action("new"), store::update) { throw SessionChangedException() } }.exceptionOrNull()
        assertTrue(error is SessionChangedException)
        assertTrue(store.pending.isEmpty())
    }

    @Test fun cancelledNewestWriteCannotLeaveAnOlderValueToReplay() = runTest {
        val store = PendingStore(listOf(action("chapter-5")))
        runCatching { CloudMutationQueue().submit(action("chapter-6"), store::update) { throw CancellationException("left screen") } }
        assertEquals(listOf(action("chapter-6")), store.pending)
    }
}
