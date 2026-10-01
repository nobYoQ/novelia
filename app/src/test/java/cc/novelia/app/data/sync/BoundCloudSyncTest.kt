package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.auth.SessionState
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundCloudSyncTest {
    private fun profile(account: String) = Profile(account, "member", 0, Long.MAX_VALUE)
    private val favorite = PendingAction("favorite", "alice", "PUT", "user/favored-web/folder/syosetu/one")
    private val history = PendingAction("history", "alice", "PUT", "user/read-history/syosetu/one")

    @Test fun waitingSyncCannotSendAfterAccountSwitchOrSameAccountRelogin() = runTest {
        for(nextAccount in listOf("bob", "alice")) {
            val session = SessionState("initial", profile("alice"))
            val binding = session.capture()
            val gate = BoundCloudSync()
            val release = CompletableDeferred<Unit>()
            val holder = launch { gate.run(binding, { session.tokenFor(it) }) { release.await() } }
            runCurrent()
            var requests = 0
            var failure: Throwable? = null
            val waiting = launch {
                failure = runCatching { gate.run(binding, { session.tokenFor(it) }) { requests++ } }.exceptionOrNull()
            }
            runCurrent()
            assertEquals(0, requests)
            session.clear()
            session.commit(session.capture(), "new-login", profile(nextAccount), allowAccountChange = true)
            release.complete(Unit)
            holder.join()
            waiting.join()
            assertTrue("Stale binding was accepted after login as $nextAccount", failure is SessionChangedException)
            assertEquals(0, requests)
            gate.run(session.capture(), { session.tokenFor(it) }) { requests++ }
            assertEquals(1, requests)
        }
    }

    @Test fun cancellingAWaitingSyncDoesNotSendOrBlockTheNextRequest() = runTest {
        val session = SessionState("initial", profile("alice"))
        val binding = session.capture()
        val gate = BoundCloudSync()
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.run(binding, { session.tokenFor(it) }) { release.await() } }
        runCurrent()
        var requests = 0
        val waiting = launch { gate.run(binding, { session.tokenFor(it) }) { requests++ } }
        runCurrent()
        waiting.cancelAndJoin()
        release.complete(Unit)
        holder.join()
        assertEquals(0, requests)
        gate.run(binding, { session.tokenFor(it) }) { requests++ }
        assertEquals(1, requests)
    }

    @Test fun singleBookReplaySendsOnlyItsOwnActionsAndKeepsEveryOtherQueueEntry() = runTest {
        val otherBook = history.copy(id = "other-book", path = "user/read-history/syosetu/one-more")
        val otherProvider = favorite.copy(id = "wenku", path = "user/favored-wenku/folder/one")
        val otherAccount = history.copy(id = "bob", account = "bob")
        val folder = favorite.copy(id = "folder", path = "user/favored-web/folder")
        var pending = listOf(favorite, otherBook, history, otherAccount, otherProvider, folder)
        val binding = SessionBinding("alice", 3)
        val requests = mutableListOf<PendingAction>()
        val result = CloudMutationQueue().replayEligible("alice",
            readPending = { pendingForSync(pending, binding, "syosetu/one") },
            updatePending = { pending = it(pending) },
        ) { requests += it }
        assertEquals(2, result.completed)
        assertEquals(listOf(favorite, history), requests)
        assertEquals(listOf(otherBook, otherAccount, otherProvider, folder), pending)
    }

    @Test fun fullAccountSelectionStillExcludesOtherAccounts() {
        val bob = history.copy(id = "bob", account = "bob")
        assertEquals(listOf(favorite, history), pendingForSync(listOf(favorite, bob, history), SessionBinding("alice", 1), null))
        assertTrue(pendingForSync(listOf(favorite, bob, history), SessionBinding(null, 2), null).isEmpty())
    }

    @Test fun staleRemovalConfirmationCannotDeleteAfterSwitchingOrRelogging() {
        val initial = LibraryState(pending = listOf(favorite), syncStatus = mapOf(
            "alice" to CloudSyncStatus(failures = mapOf(favorite.id to "offline"), blockedActions = setOf(favorite.id)),
        ))
        val owner = SessionBinding("alice", 1)
        assertSame(initial, initial.removePendingForSession(favorite, owner, SessionBinding("bob", 2)))
        assertSame(initial, initial.removePendingForSession(favorite, owner, SessionBinding("alice", 2)))
        assertSame(initial, initial.removePendingForSession(favorite, SessionBinding("bob", 2), SessionBinding("bob", 2)))
    }

    @Test fun validRemovalClearsOnlyOwnedActionAndItsFailureMetadata() {
        val owner = SessionBinding("alice", 1)
        // 即使恢复备份在两个账号中包含相同操作 ID，也应保持账号隔离。
        val bob = favorite.copy(account = "bob")
        val initial = LibraryState(pending = listOf(favorite, bob, history), syncStatus = mapOf(
            "alice" to CloudSyncStatus(failures = mapOf(favorite.id to "offline", history.id to "unavailable"), blockedActions = setOf(favorite.id)),
            "bob" to CloudSyncStatus(failures = mapOf(bob.id to "forbidden"), blockedActions = setOf(bob.id)),
        ))
        val updated = initial.removePendingForSession(favorite, owner, owner)
        assertEquals(listOf(bob, history), updated.pending)
        assertEquals(mapOf(history.id to "unavailable"), updated.syncStatus.getValue("alice").failures)
        assertTrue(updated.syncStatus.getValue("alice").blockedActions.isEmpty())
        assertEquals(initial.syncStatus["bob"], updated.syncStatus["bob"])
    }
}
