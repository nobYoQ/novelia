package cc.novelia.app.data.model

import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class FavoriteFolderOrderTest {
    @Test fun originalAndNameOrdersKeepTheDefaultFolderFirst() {
        val state = appJson.decodeFromString<LibraryState>("""{"folders":["默认收藏","中篇","阿尔法","北方"]}""")
        assertEquals(listOf(DEFAULT_FOLDER, "中篇", "阿尔法", "北方"), state.orderedLocalFavoriteFolders())
        val sorted = state.copy(localFavoriteFolderOrder = FavoriteFolderOrder(FavoriteFolderSort.Name))
        assertEquals(listOf(DEFAULT_FOLDER, "阿尔法", "北方", "中篇"), sorted.orderedLocalFavoriteFolders())
        assertEquals(state.folders, sorted.folders)
    }

    @Test fun manualRanksSurviveRenameAndStorageAndNewFoldersAppend() {
        val original = LibraryState().createShelfFolder("未读").createShelfFolder("收藏")
        val favorite = original.folderIds.getValue("收藏")
        val pending = original.folderIds.getValue("未读")
        val manual = original.copy(localFavoriteFolderOrder = FavoriteFolderOrder(FavoriteFolderSort.Manual, listOf(favorite, pending)))
            .renameShelfFolder("收藏", "精选").createShelfFolder("新建")
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(manual))
        assertEquals(listOf(DEFAULT_FOLDER, "精选", "未读", "新建"), restored.orderedLocalFavoriteFolders())
        val deleted = restored.deleteShelfFolder("精选").createShelfFolder("精选")
        assertEquals(listOf(pending), deleted.localFavoriteFolderOrder.manualIds)
        assertEquals(listOf(DEFAULT_FOLDER, "未读", "新建", "精选"), deleted.orderedLocalFavoriteFolders())
    }

    @Test fun cloudPreferencesAreIsolatedAndNeverChangeTheLibrarySyncProjection() {
        val original = LibraryState()
        val names = FavoriteFolderOrder(FavoriteFolderSort.Name)
        val manual = FavoriteFolderOrder(FavoriteFolderSort.Manual, listOf("gone", "z", "a"))
        val state = original.withCloudFavoriteFolderOrder("alice", false, names)
            .withCloudFavoriteFolderOrder("alice", true, manual)
            .withCloudFavoriteFolderOrder("bob", false, manual)
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        assertEquals(names, restored.cloudFavoriteFolderOrder("alice", false))
        assertEquals(manual, restored.cloudFavoriteFolderOrder("alice", true))
        assertEquals(manual, restored.cloudFavoriteFolderOrder("bob", false))
        assertEquals(FavoriteFolderOrder(), restored.cloudFavoriteFolderOrder("bob", true))
        val raw = listOf(Folder("a", "A"), Folder("z", "Z"), Folder("default", "默认收藏夹"), Folder("n", "新建"))
        assertEquals(listOf("default", "z", "a", "n"), restored.cloudFavoriteFolderOrder("bob", false)
            .ordered(raw, CLOUD_DEFAULT_FOLDER_ID).map(Folder::id))
        assertTrue(WebDavProjection.affectedLibraryDomains(original, restored).isEmpty())
        assertEquals(WebDavProjection.library(original), WebDavProjection.library(restored))
    }
}
