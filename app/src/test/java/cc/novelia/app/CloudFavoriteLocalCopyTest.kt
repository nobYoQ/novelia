package cc.novelia.app

import cc.novelia.app.data.library.withCloudFavoriteLocalCopy
import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFavoriteLocalCopyTest {
    private val book = BookCard(BookRef("syosetu", "n1"), "作品")

    @Test fun readingProgressDoesNotCreateLocalFavorite() {
        val state = LibraryState().withReadingPosition(book.ref, Position("chapter-1"))
        assertTrue(state.books.isEmpty())
        assertEquals("chapter-1", state.positions[book.ref.key]?.chapterId)
    }

    @Test fun cloudFavoriteCreatesLocalCopyOnlyWhileEnabled() {
        assertEquals(book, LibraryState().withCloudFavoriteLocalCopy(book).books.single().book)
        assertTrue(LibraryState(autoSaveCloudFavoritesLocally = false).withCloudFavoriteLocalCopy(book).books.isEmpty())
    }

    @Test fun cloudFavoritePreservesExistingManualFolder() {
        val saved = SavedBook(book, folder = "自定义收藏夹")
        val state = LibraryState(books = listOf(saved))
        assertEquals(state, state.withCloudFavoriteLocalCopy(book.copy(title = "云端标题")))
    }

    @Test fun preferenceSurvivesSettingsBackupAndOldSettingsDefaultToOn() {
        val backup = SettingsBackup(autoSaveCloudFavoritesLocally = false)
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").autoSaveCloudFavoritesLocally)
        assertTrue(appJson.decodeFromString<LibraryState>("{}").autoSaveCloudFavoritesLocally)
    }
}
