package cc.novelia.app

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.ui.shelf.bookFavoriteState
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
        assertTrue(state.label.startsWith("已云端收藏"))
    }

    @Test fun localCopyDoesNotOverrideCloudMembership() {
        val state = bookFavoriteState(ref, true, "reading", "alice", emptyList())
        assertTrue(state.local)
        assertEquals("reading", state.cloudFolder)
        assertTrue(state.label.startsWith("已云端收藏"))
    }

    @Test fun pendingMoveAndRemovalOverrideStaleCloudDetailsOnlyForThisAccountAndBook() {
        val otherAccount = favorite.copy(id = "bob", account = "bob", method = "DELETE")
        val otherBook = favorite.copy(id = "other", method = "DELETE", path = "user/favored-web/reading/syosetu/n1234")
        val move = bookFavoriteState(ref, false, "old-folder", "alice", listOf(favorite, otherAccount, otherBook))
        assertEquals("reading", move.cloudFolder)
        assertEquals("PUT", move.pendingMethod)

        val removal = favorite.copy(id = "remove", method = "DELETE")
        val removed = bookFavoriteState(ref, false, "reading", "alice", listOf(favorite, removal))
        assertFalse(removed.isSaved)
        assertNull(removed.cloudFolder)
        assertEquals("DELETE", removed.pendingMethod)
        assertTrue(bookFavoriteState(ref, true, "reading", "alice", listOf(removal)).isSaved)
    }

    @Test fun signedOutAndLocalBooksDoNotInheritCloudMembership() {
        assertFalse(bookFavoriteState(ref, false, "reading", null, listOf(favorite)).isSaved)
        assertNull(bookFavoriteState(BookRef("local", "n123"), true, "reading", "alice", emptyList()).cloudFolder)
        assertFalse(bookFavoriteState(ref, false, "", "alice", emptyList()).isSaved)
    }

    @Test fun wenkuFavoritesUseTheirOwnResourceAndPendingFolder() {
        val wenku = BookRef("wenku", "n123")
        val action = favorite.copy(path = "user/favored-wenku/wenku-folder/${wenku.id}")
        assertEquals("wenku-folder", bookFavoriteState(wenku, false, null, "alice", listOf(action)).cloudFolder)
        assertFalse(bookFavoriteState(ref, false, null, "alice", listOf(action)).isSaved)
    }
}
