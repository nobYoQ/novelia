package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfBookOrderTest {
    private val one = SavedBook(BookCard(BookRef("local", "one"), "甲"), pinned = true, addedAt = 3)
    private val hidden = SavedBook(BookCard(BookRef("local", "hidden"), "乙"), folder = "其他", addedAt = 2)
    private val parent = SavedBook(BookCard(BookRef("wenku", "work"), "丙"), addedAt = 1)
    private val volume = SavedBook(BookCard(BookRef("local", "volume"), "第 1 卷"), parentWenkuKey = parent.book.ref.key, addedAt = 1)
    private val newBook = SavedBook(BookCard(BookRef("local", "new"), "新书"), addedAt = 4)

    @Test fun movingFilteredBooksPersistsTheOrderAndKeepsVolumesWithTheirParent() {
        val state = LibraryState(books = listOf(one, hidden, parent, volume), folders = listOf("默认收藏", "其他"))
            .withShelfSort(CUSTOM_SHELF_SORT)
            .withShelfBookMoved(parent.book.ref.key, one.book.ref.key, after = false)
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        assertEquals(CUSTOM_SHELF_SORT, restored.shelfSort)
        assertEquals(listOf(parent, one, hidden), restored.visibleBooks())
        assertEquals(listOf(parent, one), restored.visibleBooks("默认收藏"))
        assertEquals(listOf(volume), restored.shelfGroups(false, "全部", "", CUSTOM_SHELF_SORT).first().volumes)
        assertEquals(one, restored.withShelfSort(0).visibleBooks().first())
        assertEquals(restored.visibleBooks(), restored.withShelfSort(0).withShelfSort(CUSTOM_SHELF_SORT).visibleBooks())
    }

    @Test fun newBooksAndMovedLocalVolumesKeepAUsableOrderAfterRemoval() {
        val state = LibraryState(books = listOf(one, hidden, parent, volume)).withShelfSort(CUSTOM_SHELF_SORT)
            .let { it.copy(books = it.books + newBook) }
        assertEquals(newBook, state.visibleBooks().last())
        val moved = state.withShelfBookMoved(volume.book.ref.key, one.book.ref.key, after = false)
        assertEquals(listOf(volume, one, hidden, newBook), moved.visibleBooks(localOnly = true))
        assertEquals(state.visibleBooks(), moved.visibleBooks())
        val removed = moved.withoutBook(one.book.ref)
        assertTrue(one.book.ref.key !in removed.shelfBookOrder)
        assertEquals(removed, removed.withShelfBookMoved(volume.book.ref.key, one.book.ref.key, after = false))
    }

    private fun LibraryState.visibleBooks(folder: String = "全部", localOnly: Boolean = false) =
        shelfGroups(localOnly, folder, "", shelfSort).map { it.saved }
}
