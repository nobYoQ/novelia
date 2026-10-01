package cc.novelia.app.data.updates

import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BookUpdateStateTest {
    private val ref = BookRef("syosetu", "updates")
    private val book = BookCard(ref, "更新测试", total = 10, translations = mapOf("gpt" to 10))

    @Test fun detailsAndWorkerAccumulateExactlyOnceAcrossPersistenceAndFolderMoves() {
        val initial = LibraryState().withSavedBook(book)
        val refreshed = initial.withSavedBook(book.copy(total = 12), "追更")
        assertEquals(10, refreshed.updateSnapshots.getValue(ref.key).total)
        val detected = refreshed.withBookUpdate(book.copy(total = 12), 20)
        assertEquals(2, detected.bookUpdates.getValue(ref.key).newChapters)
        assertTrue(detected.books.single().hasUpdates)
        assertEquals("追更", detected.books.single().folder)
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(detected))
        val again = restored.withBookUpdate(book.copy(total = 12), 30)
        assertEquals(2, again.bookUpdates.getValue(ref.key).newChapters)
        assertEquals(3, again.withBookUpdate(book.copy(total = 13), 40).bookUpdates.getValue(ref.key).newChapters)
    }

    @Test fun aLateOldResponseDoesNotRegressTotalsOrCreateDuplicateUpdates() {
        val latest = LibraryState().withSavedBook(book).withBookUpdate(book.copy(total = 12), 30)
        assertSame(latest, latest.withBookUpdate(book, 20))
        assertEquals(2, latest.withBookUpdate(book.copy(total = 12), 40).bookUpdates.getValue(ref.key).newChapters)
        val moved = latest.withSavedBook(book, "旧详情收藏夹")
        assertEquals(12, moved.books.single().book.total)
        assertEquals(12, moved.updateSnapshots.getValue(ref.key).total)
        assertEquals(2, moved.bookUpdates.getValue(ref.key).newChapters)
    }

    @Test fun readingBeforeTheCheckDoesNotResurrectChapterBadgesButLaterContentStillDoes() {
        val read = LibraryState().withSavedBook(book).withReadingPosition(ref,
            Position("12", updatedAt = 25, chapterIndex = 11, chapterCount = 12))
        val checked = read.withBookUpdate(book.copy(total = 12), 30)
        assertFalse(checked.books.single().hasUpdates)
        assertNull(checked.bookUpdates[ref.key])
        val next = checked.withBookUpdate(book.copy(total = 13), 40)
        assertTrue(next.books.single().hasUpdates)
        assertEquals(1, next.bookUpdates.getValue(ref.key).newChapters)
    }

    @Test fun acknowledgingUpdatesPreservesTranslationFreshnessAndTheNextUpdateBaseline() {
        val updated = LibraryState().withSavedBook(book).withBookUpdate(book.copy(total = 11,
            translations = mapOf("gpt" to 11)), 20)
        val read = updated.withAcknowledgedBookUpdates(ref)
        assertFalse(read.books.single().hasUpdates)
        assertFalse(read.bookUpdates.getValue(ref.key).hasChanges)
        assertEquals(20L, read.bookUpdates.getValue(ref.key).latestTranslationAt(listOf("gpt")))
        assertEquals(updated.updateSnapshots, read.updateSnapshots)
        val checkedAgain = read.withBookUpdate(book.copy(total = 11, translations = mapOf("gpt" to 11)), 30)
        assertFalse(checkedAgain.books.single().hasUpdates)
        val newer = checkedAgain.withBookUpdate(book.copy(total = 12, translations = mapOf("gpt" to 12)), 40)
        assertEquals(1, newer.bookUpdates.getValue(ref.key).newChapters)
        assertEquals(mapOf("gpt" to 1), newer.bookUpdates.getValue(ref.key).translations)
    }

    @Test fun unknownCountsAndUnsavedBooksDoNotInventOldContentUpdates() {
        assertFalse(detectBookUpdate(BookUpdateSnapshot(), book.updateSnapshot(10), false).hasChanges)
        val state = LibraryState(books = listOf(SavedBook(book.copy(total = 0, translations = emptyMap()))))
        assertFalse(state.withBookUpdate(book, 10).books.single().hasUpdates)
        val empty = LibraryState()
        assertSame(empty, empty.withBookUpdate(book, 10))
    }
}
