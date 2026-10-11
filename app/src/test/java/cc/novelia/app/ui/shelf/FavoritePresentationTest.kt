package cc.novelia.app.ui.shelf

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.PendingAction
import org.junit.Assert.*
import org.junit.Test

class FavoritePresentationTest {
    private val ref = BookRef("syosetu", "n123")
    private val favorite = PendingAction("favorite", "alice", "PUT", "user/favored-web/reading/${ref.key}")

    @Test fun cloudFavoriteIsSavedWithoutALocalShelfCopy() {
        val state = bookFavoriteState(ref, false, "reading", "alice", emptyList())
        assertTrue(state.isSaved)
        assertFalse(state.local)
        assertEquals("reading", state.cloudFolder)
        assertEquals("已云端收藏", state.cloudLabel)
        assertEquals("收藏到本地", state.localLabel)
    }

    @Test fun localCopyDoesNotOverrideCloudMembership() {
        val state = bookFavoriteState(ref, true, "reading", "alice", emptyList())
        assertTrue(state.local)
        assertEquals("reading", state.cloudFolder)
        assertEquals("已云端收藏", state.cloudLabel)
        assertEquals("已本地收藏", state.localLabel)
    }

    @Test fun pendingMoveAndRemovalOverrideStaleCloudDetailsOnlyForThisAccountAndBook() {
        val otherAccount = favorite.copy(id = "bob", account = "bob", method = "DELETE")
        val otherBook = favorite.copy(id = "other", method = "DELETE", path = "user/favored-web/reading/syosetu/n1234")
        val move = bookFavoriteState(ref, false, "old-folder", "alice", listOf(favorite, otherAccount, otherBook))
        assertEquals("reading", move.cloudFolder)
        assertEquals("PUT", move.pendingMethod)
        assertEquals("云端收藏待同步", move.cloudLabel)
        assertEquals("收藏到本地", move.localLabel)

        val removal = favorite.copy(id = "remove", method = "DELETE")
        val removed = bookFavoriteState(ref, false, "reading", "alice", listOf(favorite, removal))
        assertFalse(removed.isSaved)
        assertNull(removed.cloudFolder)
        assertEquals("DELETE", removed.pendingMethod)
        assertEquals("取消云端收藏待同步", removed.cloudLabel)
        assertTrue(bookFavoriteState(ref, true, "reading", "alice", listOf(removal)).isSaved)
    }

    @Test fun signedOutAndLocalBooksDoNotInheritCloudMembership() {
        assertFalse(bookFavoriteState(ref, false, "reading", null, listOf(favorite)).isSaved)
        assertNull(bookFavoriteState(BookRef("local", "n123"), true, "reading", "alice", emptyList()).cloudFolder)
        assertFalse(bookFavoriteState(ref, false, "", "alice", emptyList()).isSaved)
        val local = bookFavoriteState(ref, true, "reading", null, listOf(favorite))
        assertEquals("已本地收藏", local.localLabel)
        assertEquals("收藏到云端", local.cloudLabel)
    }

    @Test fun wenkuFavoritesUseTheirOwnResourceAndPendingFolder() {
        val wenku = BookRef("wenku", "n123")
        val action = favorite.copy(path = "user/favored-wenku/wenku-folder/${wenku.id}")
        assertEquals("wenku-folder", bookFavoriteState(wenku, false, null, "alice", listOf(action)).cloudFolder)
        assertFalse(bookFavoriteState(ref, false, null, "alice", listOf(action)).isSaved)
    }
}
