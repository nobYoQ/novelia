package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.booksForUpdate
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LocalSeriesTest {
    private val ref = BookRef.newLocalSeries()
    private val first = SavedBook(BookCard(BookRef("local", "a"), "第 2 卷"))
    private val second = SavedBook(BookCard(BookRef("local", "b"), "第 10 卷"))
    private val wenku = SavedBook(BookCard(BookRef("wenku", "existing"), "原文库"))
    private val position = Position("chapter", index = 15, offset = 22, textOffset = 90)
    private val initial = LibraryState(books = listOf(wenku, first, second), positions = mapOf(first.book.ref.key to position))
    private val keys = setOf(first.book.ref.key, second.book.ref.key)

    @Test fun localSeriesGroupsExistingFilesAndRoundTripsWithoutRewritingTheirIdentityOrPosition() {
        val created = initial.withMountedVolumes(wenku.book.ref.key, keys)
            .createLocalSeries(ref, " 总集 ", "默认收藏")
            .withMountedVolumes(ref.key, keys)
            .renameLocalSeries(ref, "新名称")
            .withVolumeOrder(ref.key, listOf(second.book.ref.key, first.book.ref.key))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(created))
        val group = restored.shelfGroups(false, "全部", "", 2, ShelfBookType.Series).single()
        assertEquals(ref, group.saved.book.ref)
        assertEquals("新名称", group.saved.book.title)
        assertEquals(listOf(second.book.ref, first.book.ref), group.volumes.map { it.book.ref })
        assertEquals(first.book.ref, restored.nextMountedVolume(second.book.ref)?.book?.ref)
        assertEquals(initial.positions, restored.positions)
        assertEquals(initial.books.map { it.book }, restored.books.filter { !it.book.ref.isLocalSeries }.map { it.book })
        assertEquals(listOf(first.book.ref), restored.shelfGroups(false, "全部", "第 2 卷", 2).single().volumes.map { it.book.ref })
        assertEquals(keys, restored.shelfGroups(true, "全部", "", 2, ShelfBookType.Series).map { it.saved.book.ref.key }.toSet())
        assertTrue(restored.books.first { it.book.ref == wenku.book.ref }.volumeOrder.isEmpty())
    }

    @Test fun dissolvingAndOnlineSyncNeverTreatTheSeriesAsAContentSource() {
        val mounted = initial.createLocalSeries(ref, "总集", "默认收藏").withMountedVolumes(ref.key, keys)
        assertEquals(listOf(wenku), booksForUpdate(mounted.books, lastCheckedKey = null))
        val favorites = WebDavProjection.library(mounted).getValue(SyncDomain.FAVORITES)
        assertTrue(wenku.book.ref.key in favorites)
        assertFalse(ref.key in favorites)
        assertFalse(favorites.keys.any { it in keys })

        val plan = mounted.bookDeletionPlan(refs = setOf(ref))
        assertEquals(setOf(ref), plan.books)
        assertTrue(plan.downloadIds.isEmpty() && plan.linkIds.isEmpty())
        val dissolved = mounted.withDeletedBookRecords(plan.books, eraseReadingData = true, plan.linkIds)
        assertEquals(initial.positions, dissolved.positions)
        assertEquals(initial.books, dissolved.books)
    }
}
