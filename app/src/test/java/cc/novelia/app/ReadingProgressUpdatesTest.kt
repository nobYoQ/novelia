package cc.novelia.app

import cc.novelia.app.data.library.acknowledgeReadChapterUpdates
import cc.novelia.app.data.library.acknowledgeCompletedBookUpdates
import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.updates.BookUpdateSnapshot
import cc.novelia.app.data.updates.detectBookUpdate
import cc.novelia.app.data.updates.accumulate
import cc.novelia.app.ui.components.bookRowStatus
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressUpdatesTest {
    private val ref = BookRef("syosetu", "read-latest")
    private val book = SavedBook(BookCard(ref, "阅读进度测试", total = 100), hasUpdates = true)
    private val snapshot = BookUpdateSnapshot(total = 100, checkedAt = 10)
    private val library = LibraryState(books = listOf(book),
        updateSnapshots = mapOf(ref.key to snapshot), bookUpdates = mapOf(ref.key to BookUpdateInfo(newChapters = 1)))
    private val lastPage = Position("last", index = 2, offset = 30, textOffset = 400, updatedAt = 20,
        chapterIndex = 99, chapterCount = 100, paragraphCount = 2, chapterCompleted = true)

    @Test fun finishingTheLatestChapterClearsItsBadgeWithoutMovingTheAnchorOrMarkingTheBookFinished() {
        val result = library.withReadingPosition(ref, lastPage)
        assertEquals(lastPage, result.positions[ref.key])
        assertFalse(result.books.single().hasUpdates)
        assertFalse(result.bookUpdates.containsKey(ref.key))
        assertEquals(library.updateSnapshots, result.updateSnapshots)
        assertEquals("在读", result.books.single().status)
        assertEquals(result, appJson.decodeFromString<LibraryState>(appJson.encodeToString(result)))
    }

    @Test fun openingTheLastChapterOrFinishingAnEarlierChapterDoesNotClearTheBadge() {
        for(position in listOf(lastPage.copy(chapterCompleted = false), lastPage.copy(chapterIndex = 98),
            lastPage.copy(chapterIndex = null), lastPage.copy(chapterCount = 0))) {
            val result = library.withReadingPosition(ref, position)
            assertTrue(result.books.single().hasUpdates)
            assertEquals(library.bookUpdates, result.bookUpdates)
        }
    }

    @Test fun aStaleDirectoryCannotAcknowledgeNewerKnownChapters() {
        for(newer in listOf(library.copy(books = listOf(book.copy(book = book.book.copy(total = 101)))),
            library.copy(updateSnapshots = mapOf(ref.key to snapshot.copy(total = 101))))) {
            val result = newer.withReadingPosition(ref, lastPage)
            assertTrue(result.books.single().hasUpdates)
            assertEquals(newer.bookUpdates, result.bookUpdates)
        }
    }

    @Test fun finishingChaptersPreservesTranslationFreshnessAndIndependentVolumeChanges() {
        val update = BookUpdateInfo(checkedAt = 10, newChapters = 1, translations = mapOf("gpt" to 3),
            translationUpdatedAt = mapOf("gpt" to 9), newVolumes = 2)
        val result = library.copy(bookUpdates = mapOf(ref.key to update)).withReadingPosition(ref, lastPage)
        assertTrue(result.books.single().hasUpdates)
        assertEquals(update.copy(newChapters = 0, translations = emptyMap()), result.bookUpdates[ref.key])
        assertEquals(9L, result.bookUpdates.getValue(ref.key).latestTranslationAt(listOf("gpt")))
    }

    @Test fun finishingClearsTheFallbackBadgeForAllExistingTranslationsButKeepsCacheFreshness() {
        val engines = listOf("sakura", "gpt", "youdao")
        val update = BookUpdateInfo(checkedAt = 10, newChapters = 1, translations = engines.associateWith { 1 })
        val result = library.copy(bookUpdates = mapOf(ref.key to update)).withReadingPosition(ref, lastPage)
        assertFalse(result.books.single().hasUpdates)
        val remaining = result.bookUpdates.getValue(ref.key)
        assertFalse(remaining.hasChanges)
        assertTrue(remaining.translations.isEmpty())
        assertNull(bookRowStatus(book.book, result.books.single(), lastPage, remaining).updateLabel)
        assertEquals(engines.associateWith { 10L }, remaining.translationUpdatedAt)
        assertEquals(10L, remaining.latestTranslationAt(engines))
        val checkedAgain = remaining.accumulate(BookUpdateInfo(checkedAt = 30))
        assertFalse(checkedAgain.hasChanges)
        assertEquals(10L, checkedAgain.latestTranslationAt(engines))
        val decoded = appJson.decodeFromString<LibraryState>(appJson.encodeToString(result))
        assertEquals(result, decoded)
    }

    @Test fun completedRecordsFromThePreviousVersionAreReconciledWithoutChangingTheirAnchors() {
        val old = library.copy(positions = mapOf(ref.key to lastPage),
            bookUpdates = mapOf(ref.key to BookUpdateInfo(checkedAt = 10, translations = mapOf("gpt" to 1))))
        val repaired = old.acknowledgeCompletedBookUpdates()
        assertFalse(repaired.books.single().hasUpdates)
        assertEquals(old.positions, repaired.positions)
        assertEquals(old.updateSnapshots, repaired.updateSnapshots)
        assertNull(bookRowStatus(book.book, repaired.books.single(), lastPage, repaired.bookUpdates[ref.key]).updateLabel)
        assertEquals(repaired, repaired.acknowledgeCompletedBookUpdates())
    }

    @Test fun translationsDiscoveredAfterReadingStillNotifyUntilReadAgain() {
        val update = BookUpdateInfo(checkedAt = 30, translations = mapOf("gpt" to 1))
        val current = library.copy(positions = mapOf(ref.key to lastPage), bookUpdates = mapOf(ref.key to update))
            .acknowledgeCompletedBookUpdates()
        assertTrue(current.books.single().hasUpdates)
        assertEquals("有更新", bookRowStatus(book.book, current.books.single(), lastPage, current.bookUpdates[ref.key]).updateLabel)
        val reread = current.withReadingPosition(ref, lastPage.copy(updatedAt = 40))
        assertFalse(reread.books.single().hasUpdates)
        assertNull(bookRowStatus(book.book, reread.books.single(), lastPage, reread.bookUpdates[ref.key]).updateLabel)
        assertEquals(30L, reread.bookUpdates.getValue(ref.key).latestTranslationAt(listOf("gpt")))
    }

    @Test fun eachEngineUsesItsOwnUpdateTimeWhenAcknowledgingTranslations() {
        val mixed = BookUpdateInfo(checkedAt = 30, translations = mapOf("gpt" to 2, "sakura" to 1),
            translationUpdatedAt = mapOf("gpt" to 10, "sakura" to 30))
        val result = library.copy(bookUpdates = mapOf(ref.key to mixed)).withReadingPosition(ref, lastPage)
        val remaining = result.bookUpdates.getValue(ref.key)
        assertEquals(mapOf("sakura" to 1), remaining.translations)
        assertTrue(result.books.single().hasUpdates)
        assertEquals(10L, remaining.latestTranslationAt(listOf("gpt")))
        assertEquals(30L, remaining.latestTranslationAt(listOf("sakura")))
        val newUpdate = remaining.accumulate(BookUpdateInfo(checkedAt = 50, translations = mapOf("gpt" to 1)))
        assertEquals(mapOf("gpt" to 1, "sakura" to 1), newUpdate.translations)
        assertEquals(50L, newUpdate.latestTranslationAt(listOf("gpt")))
    }

    @Test fun pausedHistoryDoesNotWriteOrAcknowledgeReading() {
        val paused = library.copy(historyPaused = true)
        assertEquals(paused, paused.withReadingPosition(ref, lastPage))
    }

    @Test fun returningFromANoteKeepsSameChapterCompletionAndMetadataWithTheNewAnchor() {
        val finished = library.withReadingPosition(ref, lastPage)
        val note = Position("last", index = 1, textOffset = 12, updatedAt = 30)
        val result = finished.withReadingPosition(ref, note)
        assertEquals(note.copy(chapterIndex = 99, chapterCount = 100, paragraphCount = 2, chapterCompleted = true),
            result.positions[ref.key])
        val otherChapter = note.copy(chapterId = "first")
        assertEquals(otherChapter, result.withReadingPosition(ref, otherChapter).positions[ref.key])
    }

    @Test fun aLateUpdateCheckDoesNotResurrectChaptersAlreadyRead() {
        val afterReading = library.withReadingPosition(ref, lastPage)
        val lateDelta = detectBookUpdate(snapshot.copy(total = 99), snapshot, false)
        val afterCheck = afterReading.copy(books = listOf(book), bookUpdates = mapOf(ref.key to lateDelta))
            .acknowledgeReadChapterUpdates(ref)
        assertFalse(afterCheck.books.single().hasUpdates)
        assertTrue(afterCheck.bookUpdates.isEmpty())
        val nextSnapshot = snapshot.copy(total = 101)
        val newChapter = detectBookUpdate(snapshot, nextSnapshot, false)
        val next = afterCheck.copy(books = listOf(book.copy(book = book.book.copy(total = 101))),
            updateSnapshots = mapOf(ref.key to nextSnapshot), bookUpdates = mapOf(ref.key to newChapter))
            .acknowledgeReadChapterUpdates(ref)
        assertEquals(1, next.bookUpdates.getValue(ref.key).newChapters)
        assertTrue(next.books.single().hasUpdates)
    }

    @Test fun anOldBooleanOnlyBadgeCanBeClearedWithoutAffectingOtherBooks() {
        val other = SavedBook(BookCard(BookRef("syosetu", "other"), "其他书"), hasUpdates = true)
        val result = library.copy(books = listOf(book, other), bookUpdates = emptyMap()).withReadingPosition(ref, lastPage)
        assertFalse(result.books.first().hasUpdates)
        assertEquals(other, result.books.last())
    }

    @Test fun localVolumeCompletionDoesNotClearItsParentWenkuUpdates() {
        val parent = SavedBook(BookCard(BookRef("wenku", "series"), "文库系列"), hasUpdates = true)
        val local = SavedBook(BookCard(BookRef("local", "volume"), "分卷", total = 100), parentWenkuKey = parent.book.ref.key)
        val state = LibraryState(books = listOf(parent, local),
            bookUpdates = mapOf(parent.book.ref.key to BookUpdateInfo(newVolumes = 1)))
        val result = state.withReadingPosition(local.book.ref, lastPage)
        assertEquals(parent, result.books.first())
        assertEquals(state.bookUpdates, result.bookUpdates)
    }
}
