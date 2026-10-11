package cc.novelia.app.data.storage

import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.shelfGroups
import cc.novelia.app.data.library.withDownloadedVolume
import cc.novelia.app.data.library.withReadingStatus
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import org.junit.Assert.*
import org.junit.Test

class LibraryOptimizationsTest {
    private val series = SavedBook(BookCard(BookRef("wenku", "series"), "系列", authors = listOf("青山作者")), status = "想读")
    private val first = SavedBook(BookCard(BookRef("local", "one"), "第一卷"), status = "读完", parentWenkuKey = series.book.ref.key)
    private val second = SavedBook(BookCard(BookRef("local", "two"), "第二卷"), status = "在读", parentWenkuKey = series.book.ref.key)
    private val local = SavedBook(BookCard(BookRef("local", "standalone"), "独立文件"), status = "想读")
    private val web = SavedBook(BookCard(BookRef("syosetu", "web"), "网络故事", authors = listOf("Another AUTHOR")), status = "在读")
    private val library = LibraryState(books = listOf(series, first, second, local, web))

    @Test fun mountedFilesBelongToWenkuAndDanglingMountsRemainLocal() {
        val wenku = library.shelfGroups(false, "全部", "", 2, ShelfBookType.Wenku).single()
        assertEquals(series, wenku.saved)
        assertEquals(setOf(first, second), wenku.volumes.toSet())
        assertEquals(listOf(local), library.shelfGroups(false, "全部", "", 0, ShelfBookType.Local).map { it.saved })
        assertEquals(listOf(web), library.shelfGroups(false, "全部", "", 0, ShelfBookType.Web).map { it.saved })
        val dangling = library.copy(books = listOf(first))
        assertEquals(first, dangling.shelfGroups(false, "全部", "", 0, ShelfBookType.Local).single().saved)
        assertTrue(dangling.shelfGroups(false, "全部", "", 0, ShelfBookType.Wenku).isEmpty())
    }

    @Test fun authorAndStatusFiltersComposeAndContextParentsAreNotSelected() {
        val match = library.shelfGroups(false, "全部", " 青山 ", 0, ShelfBookType.Wenku, "读完").single()
        assertEquals(series, match.saved)
        assertFalse(match.matchesFilters)
        assertEquals(listOf(first), match.volumes)
        val parent = library.shelfGroups(false, "全部", "青山", 0, ShelfBookType.Wenku, "想读").single()
        assertTrue(parent.matchesFilters)
        assertTrue(parent.volumes.isEmpty())
        assertEquals(web, library.shelfGroups(false, "全部", "author", 0, status = "在读").single().saved)
        assertTrue(library.shelfGroups(false, "全部", "author", 0, status = "读完").isEmpty())
        assertEquals(setOf(first, second), library.shelfGroups(true, "全部", "青山", 0).map { it.saved }.toSet())
    }

    @Test fun bulkStatusChangesOnlySelectedEntriesAndPreservesReadingData() {
        val original = library.copy(positions = mapOf(first.book.ref.key to Position("chapter", index = 4)))
        val changed = original.withReadingStatus(setOf(first.book.ref.key, web.book.ref.key, "local/missing"), "想读")
        assertEquals(first.copy(status = "想读"), changed.books.first { it.book.ref == first.book.ref })
        assertEquals(web.copy(status = "想读"), changed.books.first { it.book.ref == web.book.ref })
        assertEquals(second, changed.books.first { it.book.ref == second.book.ref })
        assertEquals(original.positions, changed.positions)
        assertThrows(IllegalArgumentException::class.java) { original.withReadingStatus(setOf(first.book.ref.key), "unknown") }
    }

    @Test fun downloadedVolumeCreatesOneParentAndReattachesWithoutLosingExistingEdits() {
        val original = LibraryState(books = listOf(first.copy(parentWenkuKey = null)), positions = mapOf(first.book.ref.key to Position("chapter", index = 9)))
        val source = series.book.copy(favored = "cloud-folder")
        val mounted = original.withDownloadedVolume(first.book.ref, source)
        assertEquals(1, mounted.books.count { it.book.ref == source.ref })
        assertNull(mounted.books.first { it.book.ref == source.ref }.book.favored)
        assertEquals(source.ref.key, mounted.books.first { it.book.ref == first.book.ref }.parentWenkuKey)
        assertEquals(mounted, mounted.withDownloadedVolume(first.book.ref, source))
        val edited = mounted.withVolumeParent(first.book.ref.key, null).copy(books = mounted.withVolumeParent(first.book.ref.key, null).books.map {
            if(it.book.ref == source.ref) it.copy(folder = "自选收藏夹", status = "读完", pinned = true) else it
        })
        val remounted = edited.withDownloadedVolume(first.book.ref, source.copy(title = "旧标题"))
        val existing = remounted.books.first { it.book.ref == source.ref }
        assertEquals("自选收藏夹", existing.folder)
        assertEquals("读完", existing.status)
        assertEquals(series.book.title, existing.book.title)
        assertTrue(existing.pinned)
        assertEquals(source.ref.key, remounted.books.first { it.book.ref == first.book.ref }.parentWenkuKey)
        assertEquals(original.positions, remounted.positions)
    }
}
