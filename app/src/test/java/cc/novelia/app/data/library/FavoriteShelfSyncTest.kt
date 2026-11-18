package cc.novelia.app.data.library

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.*
import cc.novelia.app.data.network.CloudFavoriteFolderImport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FavoriteShelfSyncTest {
    private val web = BookCard(BookRef("syosetu", "1"), "网络作品")
    private val wenku = BookCard(BookRef("wenku", "1"), "文库作品")
    private val other = BookCard(BookRef("hameln", "1"), "另一部作品")
    private val file = BookCard(BookRef("local", "1"), "导入文件")
    private val defaults = listOf(Folder("default", "默认收藏夹"))

    @Test fun localPriorityOnlyAddsAndMovesSourceBooksAndResolvesDuplicateNamesByCurrentMembership() {
        val folders = CloudFolders(defaults + listOf(Folder("one", "待读"), Folder("two", "待读")), defaults)
        val matched = web.copy(ref = BookRef("syosetu", "2"), favored = "two")
        val cloud = CloudFavoriteFolderImport(listOf(web.copy(favored = "default"), matched, other.copy(favored = "one")), folders)
        val local = LibraryState(books = listOf(SavedBook(web, "待读"), SavedBook(wenku), SavedBook(matched, "待读"), SavedBook(file)))
        val plan = local.planFavoriteSync(FavoriteSyncPriority.Local, cloud)
        assertEquals(1, plan.additions)
        assertEquals(1, plan.moves)
        assertEquals(listOf(web.ref, wenku.ref), plan.changes.map { it.book.ref })
        assertEquals(listOf("默认收藏夹", null), plan.changes.map { it.from })
        assertEquals(listOf("one", "default"), plan.changes.map { it.target.cloudId })
        assertFalse(plan.changes.any { it.target.create })
    }

    @Test fun cloudPriorityPreservesExtrasReadingDataAndMountedVolumesAndIsIdempotent() {
        val parent = SavedBook(wenku, "旧分组", pinned = true, status = "读完", volumeOrder = listOf(file.ref.key))
        val mounted = SavedBook(file, "旧分组", parentWenkuKey = wenku.ref.key)
        val extra = SavedBook(other, "仅本地")
        val local = LibraryState(books = listOf(parent, mounted, extra), positions = mapOf(file.ref.key to Position("chapter"))).withStableFolderIds()
        val folders = CloudFolders(defaults, defaults + Folder("new", "文库新夹"))
        val cloud = CloudFavoriteFolderImport(listOf(wenku.copy(favored = "new", title = "云端标题"), web.copy(favored = "default")), folders)
        val plan = local.planFavoriteSync(FavoriteSyncPriority.Cloud, cloud)
        assertEquals(listOf("旧分组", null), plan.changes.map { it.from })
        assertEquals(listOf(true, false), plan.changes.map { it.target.create })
        assertFalse("文库新夹" in local.folders)
        val updated = local.applyCloudPriority(plan).withStableFolderIds()
        val newId = updated.folderIds.getValue("文库新夹")
        assertEquals(parent.copy(folder = "文库新夹", folderId = newId), updated.books[0])
        assertEquals(mounted.copy(folder = "文库新夹", folderId = newId), updated.books[1])
        assertEquals(local.books[2], updated.books[2])
        assertEquals(DEFAULT_FOLDER, updated.books.single { it.book.ref == web.ref }.folder)
        assertEquals(local.positions, updated.positions)
        assertTrue(updated.planFavoriteSync(FavoriteSyncPriority.Cloud, cloud).changes.isEmpty())
    }

    @Test fun executionCreatesMissingCloudFoldersOncePerKindAndReportsQueuedWrites() = runBlocking {
        val local = LibraryState(books = listOf(web, other, wenku, file).map { SavedBook(it, "共享名字") })
        val plan = local.planFavoriteSync(FavoriteSyncPriority.Local, CloudFavoriteFolderImport(emptyList(), CloudFolders(defaults, defaults)))
        val folders = mutableListOf<Pair<Boolean, String>>()
        val writes = mutableListOf<Pair<BookRef, String>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        plan.applyLocalPriority({}, { kind, name -> folders += kind to name; if(kind) "wenku-id" else "web-id" },
            { ref, id -> writes += ref to id; ref == other.ref }, { count, queued -> progress += count to queued })
        assertEquals(listOf(false to "共享名字", true to "共享名字"), folders)
        assertEquals(listOf(web.ref to "web-id", other.ref to "web-id", wenku.ref to "wenku-id"), writes)
        assertEquals(listOf(1 to 0, 2 to 1, 3 to 1), progress)
    }

    @Test fun confirmationIgnoresMetadataAndOrderingButRejectsChangedMembershipOrTargetIdentity() {
        val local = LibraryState(books = listOf(SavedBook(web, "新夹"), SavedBook(other)))
        val cloud = CloudFavoriteFolderImport(emptyList(), CloudFolders(defaults + Folder("id1", "新夹")))
        val plan = local.planFavoriteSync(FavoriteSyncPriority.Local, cloud)
        plan.requireSameChanges(local.copy(books = local.books.reversed().map { it.copy(book = it.book.copy(title = "新标题")) })
            .planFavoriteSync(FavoriteSyncPriority.Local, cloud))
        assertThrows(IllegalArgumentException::class.java) { plan.requireSameChanges(local.planFavoriteSync(FavoriteSyncPriority.Local,
            cloud.copy(folders = CloudFolders(defaults + Folder("id2", "新夹"))))) }
        assertThrows(IllegalArgumentException::class.java) { plan.requireSameChanges(local.planFavoriteSync(FavoriteSyncPriority.Local,
            cloud.copy(books = listOf(other.copy(favored = "default"))))) }
    }

    @Test fun sessionChangeAfterFolderCreationStopsBeforeTheBookWrite() {
        val local = LibraryState(books = listOf(SavedBook(web, "新夹")))
        val plan = local.planFavoriteSync(FavoriteSyncPriority.Local, CloudFavoriteFolderImport(emptyList(), CloudFolders(defaults)))
        var changed = false
        assertThrows(SessionChangedException::class.java) { runBlocking {
            plan.applyLocalPriority({ if(changed) throw SessionChangedException() }, { _, _ -> changed = true; "new-id" },
                { _, _ -> error("不能写入新账号") }, { _, _ -> error("不能报告成功") })
        } }
    }
}
