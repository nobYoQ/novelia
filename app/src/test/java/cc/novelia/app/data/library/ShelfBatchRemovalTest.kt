package cc.novelia.app.data.library

import cc.novelia.app.data.model.*
import cc.novelia.app.ui.shelf.restoreRemovedShelfBooks
import org.junit.Assert.*
import org.junit.Test

class ShelfBatchRemovalTest {
    private val parent = SavedBook(BookCard(BookRef("wenku", "batch-parent"), "文库"), folder = "系列",
        volumeOrder = listOf("local/one", "local/two"))
    private val one = SavedBook(BookCard(BookRef("local", "one"), "卷一"), folder = "系列", parentWenkuKey = parent.book.ref.key)
    private val two = one.copy(book = BookCard(BookRef("local", "two"), "卷二"))
    private val web = SavedBook(BookCard(BookRef("syosetu", "batch-web"), "网络小说"))
    private val original = LibraryState(books = listOf(parent, one, two, web), positions = mapOf(one.book.ref.key to Position("chapter", 4)),
        notes = listOf(Note("note", one.book.ref.key, "chapter", 0, "摘录", "笔记")))

    @Test fun removingSelectedBooksKeepsUnselectedVolumesAndReadingData() {
        val result = original.withoutBooks(setOf(parent.book.ref, one.book.ref))
        assertEquals(setOf(two.book.ref, web.book.ref), result.books.map { it.book.ref }.toSet())
        assertNull(result.books.first { it.book.ref == two.book.ref }.parentWenkuKey)
        assertEquals("系列", result.books.first { it.book.ref == two.book.ref }.folder)
        assertEquals(original.positions, result.positions)
        assertEquals(original.notes, result.notes)
        assertEquals(listOf(two.book.ref.key), original.withoutBooks(setOf(one.book.ref)).books.first().volumeOrder)
    }

    @Test fun undoRestoresSelectedMountsWithoutOverwritingConcurrentEdits() {
        val refs = setOf(parent.book.ref, one.book.ref)
        val removed = original.withoutBooks(refs)
        val edited = removed.copy(books = removed.books.map {
            if(it.book.ref == web.book.ref) it.copy(status = "读完") else it
        })
        val result = restoreRemovedShelfBooks(edited, original.books, refs)
        assertEquals(parent, result.books.first { it.book.ref == parent.book.ref })
        assertEquals(one, result.books.first { it.book.ref == one.book.ref })
        assertEquals(two, result.books.first { it.book.ref == two.book.ref })
        assertEquals("读完", result.books.first { it.book.ref == web.book.ref }.status)
        assertEquals(result, restoreRemovedShelfBooks(result, original.books, refs))
    }

    @Test fun undoOfVolumeDoesNotResurrectParentRemovedLater() {
        val result = restoreRemovedShelfBooks(original.withoutBooks(setOf(one.book.ref, parent.book.ref)), original.books, setOf(one.book.ref))
        assertNull(result.books.first { it.book.ref == one.book.ref }.parentWenkuKey)
        assertFalse(result.books.any { it.book.ref == parent.book.ref })
    }
}
