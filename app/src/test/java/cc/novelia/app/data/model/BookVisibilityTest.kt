package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.discover.visibleBook
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BookVisibilityTest {
    private val book = BookCard(BookRef("syosetu", "n1"), "作品")

    @Test fun localAndCloudFavoriteFiltersAreIndependentAndUseBookIdentity() {
        val cloud = book.copy(ref = BookRef("wenku", "n1"), favored = "reading")
        val saved = LibraryState(books = listOf(SavedBook(book.copy(favored = "old-account-folder"))))
        assertTrue(visibleBook(book, saved, "alice"))
        assertTrue(visibleBook(cloud, saved, "alice"))
        val localOnly = saved.copy(hideLocalFavorites = true)
        assertFalse(visibleBook(book, localOnly, "alice"))
        assertTrue(visibleBook(cloud, localOnly, "alice"))
        val cloudOnly = saved.copy(hideCloudFavorites = true)
        assertTrue(visibleBook(book, cloudOnly, "alice"))
        assertFalse(visibleBook(cloud, cloudOnly, "alice"))
        assertTrue(visibleBook(cloud, cloudOnly))
        val both = saved.copy(hideLocalFavorites = true, hideCloudFavorites = true)
        assertFalse(visibleBook(book, both, "alice"))
        assertFalse(visibleBook(cloud, both, "alice"))
        assertTrue(visibleBook(book, localOnly.copy(books = emptyList()), "alice"))
    }

    @Test fun cloudFilteringHonorsPendingChangesOnlyForTheirAccountAndProvider() {
        val addition = PendingAction("add", "alice", "PUT", "user/favored-web/reading/syosetu/n1")
        val state = LibraryState(hideCloudFavorites = true, pending = listOf(addition))
        assertFalse(visibleBook(book, state, "alice"))
        assertTrue(visibleBook(book, state, "bob"))
        assertTrue(visibleBook(book, state))
        assertTrue(visibleBook(book.copy(ref = BookRef("wenku", "n1")), state, "alice"))
        val removal = addition.copy(method = "DELETE")
        val cloud = book.copy(favored = "reading")
        assertTrue(visibleBook(cloud, state.copy(pending = listOf(addition, removal)), "alice"))
        assertFalse(visibleBook(cloud, state.copy(pending = listOf(removal, addition)), "alice"))
        assertTrue(visibleBook(book.copy(favored = ""), state.copy(pending = emptyList()), "alice"))
    }

    @Test fun automaticFiltersStillRespectManualBlocks() {
        val local = LibraryState(books = listOf(SavedBook(book)), blockedBooks = setOf(book.ref.key))
        assertFalse(visibleBook(book, local))
        assertFalse(visibleBook(book, local.copy(hideCloudFavorites = true), "alice"))
        assertFalse(visibleBook(book.copy(favored = "reading"), local.copy(hideLocalFavorites = true), "alice"))
    }

    @Test fun blockedAuthorMatchesAnyAuthorWithoutCaseSensitivity() {
        val book = BookCard(BookRef("syosetu", "n1"), "作品", authors = listOf("作者甲", "Writer"))
        assertFalse(visibleBook(book, LibraryState(blockedAuthors = setOf("writer"))))
        assertFalse(visibleBook(book, LibraryState(blockedAuthors = setOf("作者甲"))))
        assertTrue(visibleBook(book, LibraryState(blockedAuthors = setOf("作者乙"))))
    }

    @Test fun authorBlockSurvivesSettingsBackupAndOldData() {
        val backup = SettingsBackup(blockedAuthors = setOf("作者甲"))
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").blockedAuthors.isEmpty())
        assertTrue(appJson.decodeFromString<LibraryState>("{}").blockedAuthors.isEmpty())
    }
}
