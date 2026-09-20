package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.auth.SessionState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.ApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CloudSyncPolicyTest {
    private class PendingStore(var pending: List<PendingAction> = emptyList()) {
        fun update(transform: (List<PendingAction>) -> List<PendingAction>) { pending = transform(pending) }
    }
    private fun action(id: String, account: String = "alice") = PendingAction(id, account, "PUT", "user/read-history/syosetu/$id", id)

    @Test fun transientServiceFailuresAndOfflineWritesRemainQueued() = runTest {
        listOf(IOException("offline"), ApiException(503, "unavailable"), ApiException(429, "rate limited"), ApiException(408, "timeout")).forEach { failure ->
            val store = PendingStore()
            val intent = action("new")
            assertEquals(SyncFailure.RETRY, classifySyncFailure(failure))
            assertTrue(CloudMutationQueue().submit(intent, store::update) { throw failure })
            assertEquals(listOf(intent), store.pending)
        }
    }

    @Test fun authenticationAndPermissionFailuresAreNotQueuedAsOfflineWrites() = runTest {
        listOf(ApiException(401, "expired"), ApiException(403, "forbidden"), SessionChangedException()).forEach { failure ->
            val store = PendingStore()
            val actual = runCatching { CloudMutationQueue().submit(action("new"), store::update) { throw failure } }.exceptionOrNull()
            assertSame(failure, actual)
            assertTrue(store.pending.isEmpty())
        }
        assertEquals(SyncFailure.AUTHENTICATION, classifySyncFailure(ApiException(401, "expired")))
        assertEquals(SyncFailure.ACCOUNT_CHANGED, classifySyncFailure(SessionChangedException()))
        assertEquals(SyncFailure.BLOCKED, classifySyncFailure(ApiException(403, "forbidden")))
    }

    @Test fun oneForbiddenResourceDoesNotPreventOtherResourcesFromSynchronizing() = runTest {
        val rejected = action("blocked")
        val allowed = action("allowed")
        val store = PendingStore(listOf(rejected, allowed))
        val attempted = mutableListOf<String>()
        val result = CloudMutationQueue().replayEligible("alice", { store.pending }, store::update) {
            attempted += it.id
            if(it == rejected) throw ApiException(403, "forbidden")
        }
        assertEquals(listOf("blocked", "allowed"), attempted)
        assertEquals(listOf(rejected), store.pending)
        assertEquals(1, result.completed)
        assertEquals(setOf("blocked"), result.blockedActions)
        assertEquals(setOf("blocked"), result.failures.keys)
        assertFalse(result.retry)
        assertFalse(result.requiresLogin)
    }

    @Test fun blockedActionsAreSkippedAutomaticallyButCanBeRetriedManually() = runTest {
        val blocked = action("blocked")
        val allowed = action("allowed")
        val store = PendingStore(listOf(blocked, allowed))
        val queue = CloudMutationQueue()
        val attempted = mutableListOf<String>()
        val automatic = queue.replayEligible("alice", { store.pending }, store::update, blockedActions = setOf(blocked.id)) { attempted += it.id }
        assertEquals(listOf("allowed"), attempted)
        assertEquals(1, automatic.completed)
        assertEquals(listOf(blocked), store.pending)
        val manual = queue.replayEligible("alice", { store.pending }, store::update, blockedActions = emptySet()) { attempted += it.id }
        assertEquals(listOf("allowed", "blocked"), attempted)
        assertEquals(1, manual.completed)
        assertTrue(store.pending.isEmpty())
    }

    @Test fun retryableFailureStopsTheBatchWithoutDroppingOtherPendingActions() = runTest {
        val original = listOf(action("first"), action("second"))
        val store = PendingStore(original)
        val attempted = mutableListOf<String>()
        val result = CloudMutationQueue().replayEligible("alice", { store.pending }, store::update) {
            attempted += it.id
            throw ApiException(429, "rate limited")
        }
        assertEquals(listOf("first"), attempted)
        assertEquals(original, store.pending)
        assertTrue(result.retry)
        assertFalse(result.requiresLogin)
        assertTrue(result.blockedActions.isEmpty())
    }

    @Test fun expiredAuthenticationPreservesTheQueueAndRequestsLogin() = runTest {
        val original = listOf(action("first"), action("second"))
        val store = PendingStore(original)
        var attempts = 0
        val result = CloudMutationQueue().replayEligible("alice", { store.pending }, store::update) {
            attempts++
            throw ApiException(401, "expired")
        }
        assertEquals(1, attempts)
        assertEquals(original, store.pending)
        assertTrue(result.requiresLogin)
        assertFalse(result.retry)
        assertTrue(result.blockedActions.isEmpty())
    }

    @Test fun switchingAccountsPreservesUnprocessedActionsAndOtherAccounts() = runTest {
        val session = SessionState("old", Profile("alice", "member", 0, Long.MAX_VALUE))
        val binding = session.capture()
        val first = action("first")
        val second = action("second")
        val third = action("third")
        val other = action("other", "bob")
        val store = PendingStore(listOf(first, second, other, third))
        val writes = mutableListOf<String>()
        val failure = runCatching {
            CloudMutationQueue().replayEligible("alice", { store.pending }, store::update) {
                session.tokenFor(binding)
                writes += it.id
                session.clear()
            }
        }.exceptionOrNull()
        assertTrue(failure is SessionChangedException)
        assertEquals(listOf("first"), writes)
        assertEquals(listOf(second, other, third), store.pending)
    }

    @Test fun batchesOverOneHundredKeepTheRemainderAndOnlyTouchTheSelectedAccount() = runTest {
        val original = (1..125).map { action("$it") }
        val other = action("other", "bob")
        val store = PendingStore(original + other)
        val queue = CloudMutationQueue()
        val writes = mutableListOf<String>()
        val first = queue.replayEligible("alice", { store.pending }, store::update) { writes += it.id }
        assertEquals(100, first.completed)
        assertEquals(original.drop(100) + other, store.pending)
        val second = queue.replayEligible("alice", { store.pending }, store::update) { writes += it.id }
        assertEquals(25, second.completed)
        assertEquals(original.map { it.id }, writes)
        assertEquals(listOf(other), store.pending)
    }

    @Test fun cancellationRetainsTheInFlightIntentAndRemainingWork() = runTest {
        val original = listOf(action("first"), action("second"))
        val store = PendingStore(original)
        val failure = runCatching {
            CloudMutationQueue().replayEligible("alice", { store.pending }, store::update) { throw CancellationException("worker stopped") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(original, store.pending)
    }
}
