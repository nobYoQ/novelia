package cc.novelia.app

import cc.novelia.app.data.*
import cc.novelia.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class BookSyncPresentationTest {
    private val favorite = PendingAction("favorite", "alice", "PUT", "user/favored-web/folder/syosetu/n123")
    private val history = PendingAction("history", "alice", "PUT", "user/read-history/syosetu/n123")

    @Test fun pendingOperationsAppearOnTheExactBookAndRespectManualMode() {
        val states = bookSyncStates("alice", listOf(favorite, history), CloudSyncStatus(), automatic = false)
        assertEquals(setOf("syosetu/n123"), states.keys)
        assertEquals(BookSyncPhase.Pending, states.getValue("syosetu/n123").phase)
        assertEquals("收藏、阅读进度待同步", states.getValue("syosetu/n123").label)
        assertEquals("等待手动同步", states.getValue("syosetu/n123").detail)
        assertNull(states["syosetu/n1234"])
    }

    @Test fun switchedAndLoggedOutAccountsNeverSeeAnotherAccountsProgressOrErrors() {
        val other = favorite.copy(id = "other", account = "bob", path = "user/favored-web/folder/syosetu/bob-book")
        val failure = CloudSyncStatus(failures = mapOf(favorite.id to "Alice 的失败原因"))
        val pending = listOf(favorite, other)
        assertEquals(setOf("syosetu/bob-book"), bookSyncStates("bob", pending, failure, listOf(favorite)).keys)
        assertTrue(bookSyncStates(null, pending, failure, listOf(favorite)).isEmpty())
        assertEquals(BookSyncPhase.Pending, bookSyncStates("bob", pending, failure, listOf(favorite)).values.single().phase)
    }

    @Test fun retryShowsLiveProgressThenClearsWhenTheQueueCompletes() {
        val failure = CloudSyncStatus(failures = mapOf(favorite.id to "网络或服务暂不可用，将自动重试"))
        val failed = bookSyncStates("alice", listOf(favorite), failure, automatic = false).values.single()
        assertEquals(BookSyncPhase.Failed, failed.phase)
        assertEquals("网络或服务暂不可用，可手动重试", failed.detail)
        assertEquals(BookSyncPhase.Syncing, bookSyncStates("alice", listOf(favorite), failure, listOf(favorite)).values.single().phase)
        assertTrue(bookSyncStates("alice", emptyList(), failure).isEmpty())
    }

    @Test fun expiredLoginAndPendingRemovalOfferDistinctExplanations() {
        val removal = favorite.copy(method = "DELETE")
        val state = bookSyncStates("alice", listOf(removal), CloudSyncStatus(requiresLogin = true)).values.single()
        assertEquals(BookSyncPhase.LoginRequired, state.phase)
        assertEquals("取消收藏等待登录", state.label)
    }

    @Test fun parserRejectsGlobalOperationsAndKeepsWenkuSeparateFromWebBooks() {
        assertEquals("wenku/n123", pendingBookKey(favorite.copy(path = "user/favored-wenku/folder/n123")))
        assertNull(pendingBookKey(favorite.copy(path = "user/read-history/paused")))
        assertNull(pendingBookKey(favorite.copy(path = "user/read-history")))
        assertNull(pendingBookKey(favorite.copy(path = "user/favored-web/folder/syosetu/n123/extra")))
        assertNull(pendingBookKey(favorite.copy(path = "admin/read-history/syosetu/n123")))
    }

    @Test fun activeNewWriteSupersedesStaleFailureDisplayWithoutExposingOtherResources() {
        val new = favorite.copy(id = "new")
        val stale = CloudSyncStatus(failures = mapOf(favorite.id to "旧请求失败"))
        assertEquals(BookSyncPhase.Syncing, bookSyncStates("alice", listOf(new), stale, listOf(new)).values.single().phase)
        assertEquals(BookSyncPhase.Pending, bookSyncStates("alice", listOf(new), stale).values.single().phase)
    }

    @Test fun completedAndSupersededActionsDropOnlyTheirOwnPersistedErrors() {
        val other = favorite.copy(id = "other", account = "bob")
        val initial = LibraryState(pending = listOf(favorite, other), syncStatus = mapOf(
            "alice" to CloudSyncStatus(failures = mapOf(favorite.id to "offline"), blockedActions = setOf(favorite.id)),
            "bob" to CloudSyncStatus(failures = mapOf(other.id to "forbidden"), blockedActions = setOf(other.id)),
        ))
        val updated = initial.updateCloudPending { it.filterNot { action -> action.id == favorite.id } }
        assertTrue(updated.syncStatus.getValue("alice").failures.isEmpty())
        assertTrue(updated.syncStatus.getValue("alice").blockedActions.isEmpty())
        assertEquals(initial.syncStatus["bob"], updated.syncStatus["bob"])
        assertEquals(listOf(other), updated.pending)
    }
}
