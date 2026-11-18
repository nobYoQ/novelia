package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.model.applyTo
import cc.novelia.app.data.model.settingsBackup
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun automaticCopiesReuseOnlyExistingFoldersAndFallBackToTheActualDefault() {
        val state = LibraryState(folders = listOf(DEFAULT_FOLDER, "待读"))
        assertEquals("待读", state.withCloudFavoriteLocalCopy(book, "待读").books.single().folder)
        assertEquals(DEFAULT_FOLDER, state.withCloudFavoriteLocalCopy(book, "另一个").books.single().folder)
        assertEquals(state.folders, state.withCloudFavoriteLocalCopy(book, "另一个").folders)
        val folders = CloudFolders(listOf(Folder("web", "待读"), Folder("default", "改名后的默认夹")),
            listOf(Folder("wenku", "待读")))
        assertEquals("web", folders.autoCopyFolder(book.ref, "待读"))
        assertEquals("wenku", folders.autoCopyFolder(BookRef("wenku", "w1"), "待读"))
        assertEquals("default", folders.autoCopyFolder(book.ref, "另一个"))
    }

    @Test fun localCopySkipsNetworkForDisabledGuestsFilesAndKnownPendingFavorites() = runBlocking {
        val enabled = LibraryState(autoSaveLocalFavoritesToCloud = true)
        suspend fun copy(state: LibraryState, account: String? = "alice", ref: BookRef = book.ref) =
            state.copyLocalFavoriteToCloud(ref, "待读", account, { error("不应读取详情") }, { error("不应读取收藏夹") }, { error("不应写入云端") })
        assertEquals(CloudCopyResult.LocalOnly, copy(LibraryState()))
        assertEquals(CloudCopyResult.LoginRequired, copy(enabled, null))
        assertEquals(CloudCopyResult.LocalOnly, copy(enabled, ref = BookRef("local", "file")))
        val pending = PendingAction("id", "alice", "PUT", "user/favored-web/other/${book.ref.key}")
        assertEquals(CloudCopyResult.AlreadySaved, copy(enabled.copy(pending = listOf(pending))))
        assertEquals(CloudCopyResult.AlreadySaved, enabled.copyLocalFavoriteToCloud(book.ref, "待读", "alice", { "other" },
            { error("已有收藏不移动") }, { error("已有收藏不写入") }))
    }

    @Test fun localCopyUsesCurrentAccountAndCanSupersedeItsPendingRemoval() = runBlocking {
        val path = "user/favored-web/old/${book.ref.key}"
        val state = LibraryState(autoSaveLocalFavoritesToCloud = true, pending = listOf(PendingAction("id", "bob", "PUT", path)))
        val folders = CloudFolders(favoredWeb = listOf(Folder("matching", "待读")))
        val written = mutableListOf<String>()
        assertEquals(CloudCopyResult.Saved, state.copyLocalFavoriteToCloud(book.ref, "待读", "alice", { null },
            { folders }, { written += it; false }))
        val removing = state.copy(pending = state.pending + PendingAction("remove", "alice", "DELETE", path))
        assertEquals(CloudCopyResult.Queued, removing.copyLocalFavoriteToCloud(book.ref, "无同名夹", "alice", { error("以待同步操作为准") },
            { folders }, { written += it; true }))
        assertEquals(listOf("matching", "default"), written)
    }

    @Test fun preferenceSurvivesSettingsBackupAndOldSettingsDefaultToOn() {
        val backup = SettingsBackup(autoSaveCloudFavoritesLocally = false)
        assertEquals(backup, appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)))
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").autoSaveCloudFavoritesLocally)
        assertTrue(appJson.decodeFromString<LibraryState>("{}").autoSaveCloudFavoritesLocally)
        assertFalse(appJson.decodeFromString<SettingsBackup>("{}").autoSaveLocalFavoritesToCloud)
        assertFalse(appJson.decodeFromString<LibraryState>("{}").autoSaveLocalFavoritesToCloud)
        val state = LibraryState(autoSaveCloudFavoritesLocally = false, autoSaveLocalFavoritesToCloud = true)
        val restored = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(state.settingsBackup())).applyTo(LibraryState())
        assertTrue(restored.autoSaveLocalFavoritesToCloud)
        assertFalse(restored.autoSaveCloudFavoritesLocally)
    }
}
