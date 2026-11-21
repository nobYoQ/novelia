package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookMedium
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.data.model.shelfMedium
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
    private val draft = LocalSeriesEdits(" 总集 ", "", emptyList(), "", DEFAULT_FOLDER_ID, keys.toList())

    @Test fun localSeriesGroupsExistingFilesAndRoundTripsWithoutRewritingTheirIdentityOrPosition() {
        val grouped = initial.withMountedVolumes(wenku.book.ref.key, keys).createShelfFolder("长篇")
            .withEditedLocalSeries(ref, creating = true, draft)
        val created = grouped.withEditedLocalSeries(ref, creating = false, draft.copy(
            title = "新名称", authors = "作者甲、作者乙", tags = listOf("奇幻，冒险", "旧版手填", "奇幻，冒险"), description = " 第一部和第二部 ",
            folderId = grouped.folderIds.getValue("长篇"), volumeKeys = listOf(second.book.ref.key, first.book.ref.key)))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(created))
        val group = restored.shelfGroups(false, "全部", "", 2, ShelfBookType.Series).single()
        assertEquals(ref, group.saved.book.ref)
        assertEquals("新名称", group.saved.book.title)
        assertEquals(listOf("作者甲", "作者乙"), group.saved.book.authors)
        assertEquals(listOf("奇幻，冒险", "旧版手填"), group.saved.book.tags)
        assertEquals("第一部和第二部", group.saved.seriesDescription)
        assertTrue((listOf(group.saved) + group.volumes).all { it.folder == "长篇" })
        assertEquals(listOf(second.book.ref, first.book.ref), group.volumes.map { it.book.ref })
        assertEquals(first.book.ref, restored.nextMountedVolume(second.book.ref)?.book?.ref)
        assertEquals(initial.positions, restored.positions)
        assertEquals(initial.books.map { it.book }, restored.books.filter { !it.book.ref.isLocalSeries }.map { it.book })
        assertEquals(listOf(first.book.ref), restored.shelfGroups(false, "全部", "第 2 卷", 2).single().volumes.map { it.book.ref })
        assertEquals(keys, restored.shelfGroups(true, "全部", "", 2, ShelfBookType.Series).map { it.saved.book.ref.key }.toSet())
        assertTrue(restored.books.first { it.book.ref == wenku.book.ref }.volumeOrder.isEmpty())
    }

    @Test fun dissolvingAndOnlineSyncNeverTreatTheSeriesAsAContentSource() {
        val mounted = initial.withEditedLocalSeries(ref, creating = true, draft)
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

    @Test fun comicSeriesGroupsComicsAndKeepsTheirIndividualPositionsAndOrder() {
        val comic = first.copy(book = first.book.copy(medium = BookMedium.Comic))
        val otherComic = second.copy(book = second.book.copy(medium = BookMedium.Comic))
        val novel = SavedBook(BookCard(BookRef("local", "novel"), "小说"))
        val source = initial.copy(books = listOf(comic, otherComic, novel))
        val edits = draft.copy(medium = BookMedium.Comic, volumeKeys = listOf(otherComic.book.ref.key, comic.book.ref.key))
        val created = source.withEditedLocalSeries(ref, creating = true, edits)
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(created))
        val group = restored.shelfGroups(false, "全部", "", 2,
            candidates = restored.books.filter { it.book.shelfMedium == BookMedium.Comic }).single()
        assertEquals(ref, group.saved.book.ref)
        assertEquals(listOf(otherComic.book.ref, comic.book.ref), group.volumes.map { it.book.ref })
        assertEquals(source.positions, restored.positions)
        assertThrows(IllegalArgumentException::class.java) { restored.withVolumeParent(novel.book.ref.key, ref.key) }
        val detached = restored.withVolumeParent(comic.book.ref.key, null)
        assertEquals(source.positions, detached.positions)
        assertNull(detached.books.single { it.book == comic.book }.parentWenkuKey)
    }
}
