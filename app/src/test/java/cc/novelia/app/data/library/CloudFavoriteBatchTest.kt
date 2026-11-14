package cc.novelia.app.data.library

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.withStableFolderIds
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.updates.updateSnapshot
import cc.novelia.app.data.updates.withSavedBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudFavoriteBatchTest {
    private val web = BookCard(BookRef("syosetu", "n1"), "网络小说", total = 10)
    private val wenku = BookCard(BookRef("wenku", "w1"), "文库小说")

    @Test fun explicitLocalBatchIgnoresAutoCopyPreferenceAndPreservesExistingLibraryData() {
        val saved = SavedBook(web, "我的分组", pinned = true, status = "读完", addedAt = 42)
        val position = Position("c1", 3)
        val before = LibraryState(books = listOf(saved), folders = listOf("默认收藏", "我的分组"),
            positions = mapOf(web.ref.key to position), autoSaveCloudFavoritesLocally = false)
        val result = before.withCloudFavoritesAddedLocally(listOf(web.copy(title = "旧云端标题"), wenku, wenku), "云端导入")
        assertEquals(2, result.books.size)
        assertEquals(saved, result.books.first())
        assertEquals("云端导入", result.books.last().folder)
        assertEquals(position, result.positions[web.ref.key])
        assertTrue("云端导入" in result.folders)
        assertEquals(before.pending, result.pending)
        assertEquals(result, result.withCloudFavoritesAddedLocally(listOf(web, wenku), "云端导入"))
        assertTrue(wenku.ref.key in result.updateSnapshots)
    }

    @Test fun batchUsesCorrectRoutesAndReportsSuccessQueuedAndFailureIndependently() = runBlocking {
        val rejected = web.copy(ref = BookRef("hameln", "2"))
        val attempted = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val result = removeCloudFavorites(listOf(web, rejected, wenku, web), "all", {}, { path ->
            attempted += path
            when {
                path.endsWith("hameln/2") -> throw ApiException(403, "不允许操作")
                path.contains("favored-wenku") -> true
                else -> false
            }
        }, progress::add)
        assertEquals(listOf("user/favored-web/all/syosetu/n1", "user/favored-web/all/hameln/2", "user/favored-wenku/all/w1"), attempted)
        assertEquals(setOf(web.ref.key), result.removed)
        assertEquals(setOf(wenku.ref.key), result.queued)
        assertEquals(setOf(rejected.ref.key), result.failed.keys)
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test fun fullImportPreservesUpdateBaselinesForPreviouslyRemovedBooks() {
        val older = web.copy(total = 30)
        val before = LibraryState(updateSnapshots = mapOf(web.ref.key to older.updateSnapshot()))
        val imported = listOf(web.copy(updateAt = -1), wenku)
        val expected = imported.fold(before) { state, book -> state.withSavedBook(book, "默认收藏") }
        val result = before.withCloudFavoritesAddedLocally(imported, "默认收藏")
        fun comparable(saved: SavedBook) = saved.copy(addedAt = 0,
            readingStatusControl = saved.readingStatusControl?.copy(revision = "new-book"))
        assertEquals(expected.books.map(::comparable), result.books.map(::comparable))
        assertEquals(expected.updateSnapshots, result.updateSnapshots)
        assertEquals(30, result.books.first().book.total)
    }

    @Test fun cloudFolderImportSeparatesWebAndWenkuIdsAndReusesLocalNames() {
        val cloud = CloudFolders(favoredWeb = listOf(Folder("same-id", "网络收藏"), Folder("unused", "空文件夹")),
            favoredWenku = listOf(Folder("same-id", "文库收藏")))
        val before = LibraryState(folders = listOf("默认收藏", "网络收藏"), autoSaveCloudFavoritesLocally = false)
        val incoming = listOf(web.copy(favored = "same-id"), wenku.copy(favored = "same-id"), web.copy(favored = "same-id"))
        val result = before.withCloudFavoritesAddedByCloudFolders(incoming, cloud)
        assertEquals(listOf("网络收藏", "文库收藏"), result.books.map { it.folder })
        assertEquals(listOf("默认收藏", "网络收藏", "文库收藏"), result.folders)
        assertEquals(result, result.withCloudFavoritesAddedByCloudFolders(incoming, cloud))
        assertEquals(setOf(web.ref.key, wenku.ref.key), result.updateSnapshots.keys)
    }

    @Test fun cloudFolderImportMovesExistingBooksAndMountedVolumesWithoutReplacingTheirData() {
        val volume = BookCard(BookRef("local", "volume-1"), "本地分卷")
        val parent = SavedBook(wenku, "手动收藏", pinned = true, status = "读完", addedAt = 42,
            hasUpdates = true, volumesExpanded = true, volumeOrder = listOf(volume.ref.key))
        val mounted = SavedBook(volume, "手动收藏", parentWenkuKey = wenku.ref.key)
        val unrelated = SavedBook(web, "手动收藏")
        val position = Position("chapter-2", 3, textOffset = 120)
        val before = LibraryState(books = listOf(parent, mounted, unrelated), folders = listOf("默认收藏", "手动收藏"),
            positions = mapOf(volume.ref.key to position), updateSnapshots = mapOf(wenku.ref.key to wenku.updateSnapshot())).withStableFolderIds()
        val cloud = CloudFolders(favoredWenku = listOf(Folder("reading", "文库收藏")))
        val result = before.withCloudFavoritesAddedByCloudFolders(listOf(wenku.copy(title = "云端旧标题", favored = "reading")), cloud).withStableFolderIds()
        val folderId = result.folderIds.getValue("文库收藏")
        assertEquals(before.books[0].copy(folder = "文库收藏", folderId = folderId), result.books[0])
        assertEquals(before.books[1].copy(folder = "文库收藏", folderId = folderId), result.books[1])
        assertEquals(before.books[2], result.books[2])
        assertEquals(before.positions, result.positions)
        assertEquals(before.updateSnapshots, result.updateSnapshots)
        assertEquals(before.pending, result.pending)
        assertEquals(before.folderIds.getValue("手动收藏"), result.folderIds.getValue("手动收藏"))
    }

    @Test fun distinctCloudFoldersWithTheSameNameShareOneLocalFolder() {
        val cloud = CloudFolders(favoredWeb = listOf(Folder("one", "在读")), favoredWenku = listOf(Folder("two", "在读")))
        val result = LibraryState().withCloudFavoritesAddedByCloudFolders(listOf(web.copy(favored = "one"), wenku.copy(favored = "two")), cloud)
        assertEquals(1, result.folders.count { it == "在读" })
        assertTrue(result.books.all { it.folder == "在读" })
    }

    @Test fun unresolvableCloudFolderDoesNotPartiallyMoveOrImportBooks() {
        val before = LibraryState(books = listOf(SavedBook(web, "手动收藏")))
        val cloud = CloudFolders(favoredWeb = listOf(Folder("reading", "在读")))
        assertThrows(IllegalArgumentException::class.java) {
            before.withCloudFavoritesAddedByCloudFolders(listOf(web.copy(favored = "reading"), wenku.copy(favored = "missing")), cloud)
        }
        assertEquals("手动收藏", before.books.single().folder)
        assertEquals(listOf("默认收藏"), before.folders)
    }

    @Test fun expiredAuthenticationDoesNotKeepSendingOtherBooks() = runBlocking {
        var requests = 0
        val result = removeCloudFavorites(listOf(web, wenku), "default", {}, {
            requests++
            throw ApiException(401, "请重新登录")
        })
        assertEquals(1, requests)
        assertEquals(setOf(web.ref.key, wenku.ref.key), result.failed.keys)
    }

    @Test fun accountChangeStopsTheBatchBeforeAnyRequestForTheNewAccount() = runBlocking {
        var changed = false
        var requests = 0
        try {
            removeCloudFavorites(listOf(web, wenku), "default", { if(changed) throw SessionChangedException() }, {
                requests++; changed = true; false
            })
            fail("会话变化必须中止批处理")
        } catch(expected: SessionChangedException) { assertEquals(1, requests) }
    }

    @Test fun cancellationIsNotReportedAsAnOrdinaryBookFailure() = runBlocking {
        var requests = 0
        try {
            removeCloudFavorites(listOf(web, wenku), "default", {}, {
                requests++
                throw CancellationException("cancelled")
            })
            fail("取消必须继续传播")
        } catch(expected: CancellationException) { assertEquals(1, requests) }
    }
}
