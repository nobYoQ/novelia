package cc.novelia.app.data.library

import cc.novelia.app.data.model.*
import cc.novelia.app.data.updates.withBookUpdate
import cc.novelia.app.data.updates.withSavedBook
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutomaticReadingStatusTest {
    private val json = Json { encodeDefaults = true }
    private val ref = BookRef("syosetu", "n-status")
    private val book = BookCard(ref, "阅读状态测试", total = 10)
    private val position = Position("last", chapterIndex = 9, chapterCount = 10, paragraphCount = 3)
    private fun state() = LibraryState().withSavedBook(book)
    private fun LibraryState.read(point: Position = position, end: Boolean = false, catalog: Boolean = true) =
        withReadingActivity(ref, readingStatusToken(ref)!!, point, end, catalog)
    private fun LibraryState.saved() = books.single { it.book.ref == ref }

    @Test fun enabledByDefaultAndNewBooksStartAutomaticallyAsWantToRead() {
        assertTrue(state().autoReadingStatus)
        assertEquals("想读", state().saved().status)
        assertTrue(state().saved().readingStatusControl!!.automatic)
        val withHistory = LibraryState(positions = mapOf(ref.key to position)).withSavedBook(book)
        assertEquals("在读", withHistory.saved().status)
    }

    @Test fun missingLegacyFieldsPreserveEveryStatusAndDefaultSettingEnabled() {
        for(status in readingStatuses) {
            val legacy = Json.decodeFromString<LibraryState>("""{"books":[{"book":{"ref":{"provider":"syosetu","id":"n-status"},"title":"旧书"},"status":"$status"}]}""")
            assertTrue(legacy.autoReadingStatus)
            assertNull(legacy.saved().readingStatusControl)
            assertEquals(status, legacy.read(end = true).saved().status)
            val roundTrip = json.decodeFromString<LibraryState>(json.encodeToString(legacy))
            assertEquals(legacy, roundTrip)
        }
    }

    @Test fun savingOrRestoringOneHundredPercentProgressDoesNotManufactureReadingActivity() {
        val next = state().withReadingPosition(ref, position.copy(chapterCompleted = true))
        assertEquals("想读", next.saved().status)
        assertEquals("在读", next.read().saved().status)
        assertEquals("读完", next.read(end = true).saved().status)
    }

    @Test fun completionRequiresCurrentEndNonemptyBodyAndCompleteCurrentCatalog() {
        assertEquals("在读", state().read(end = false).saved().status)
        assertEquals("在读", state().read(end = true, catalog = false).saved().status)
        assertEquals("在读", state().read(position.copy(chapterIndex = 8), end = true).saved().status)
        assertEquals("在读", state().read(position.copy(chapterIndex = null, chapterCount = null), end = true).saved().status)
        assertEquals("想读", state().read(position.copy(paragraphCount = 0), end = true).saved().status)
        assertEquals("在读", state().read(position.copy(chapterCount = 9, chapterIndex = 8), end = true).saved().status)
        assertEquals("读完", state().read(end = true).saved().status)
    }

    @Test fun rereadingEarlierChaptersNeverDowngradesAutomaticCompletion() {
        val finished = state().read(end = true)
        val reread = finished.read(position.copy(chapterId = "first", chapterIndex = 0))
        assertEquals("读完", reread.saved().status)
        assertEquals(10, reread.saved().readingStatusControl!!.completedChapterCount)
    }

    @Test fun restoringAfterSpeechCompletionUsesItsCertifiedCatalogWithoutAStoredPosition() {
        val initial = LibraryState().withSavedBook(book.copy(total = 0))
        val finished = initial.read(end = true)
        assertTrue(finished.positions.isEmpty())
        val restored = finished.withReadingStatus(setOf(ref.key), "想读").withAutomaticReadingStatus(setOf(ref.key))
        assertEquals("读完", restored.saved().status)
        assertTrue(restored.saved().readingStatusControl!!.valid)
    }

    @Test fun manualSelectionProtectsAllStatusesIncludingSelectingTheCurrentStatus() {
        for(status in readingStatuses) {
            val initial = state().read()
            val oldToken = initial.readingStatusToken(ref)!!
            val manual = initial.withReadingStatus(setOf(ref.key), status)
            assertFalse(manual.saved().readingStatusControl!!.automatic)
            assertNotEquals(oldToken.revision, manual.readingStatusToken(ref)!!.revision)
            assertEquals(status, manual.withReadingActivity(ref, oldToken, position, true, true).saved().status)
            assertEquals(status, manual.read(end = true).saved().status)
            assertEquals(status, manual.withReadingPosition(ref, position.copy(chapterCompleted = true)).saved().status)
        }
    }

    @Test fun disabledSettingPreservesPositionAndReenablingDoesNotReleaseManualProtection() {
        val initial = state()
        val oldToken = initial.readingStatusToken(ref)!!
        val off = initial.withAutoReadingStatus(false)
        assertNull(off.readingStatusToken(ref))
        val stored = off.withReadingPosition(ref, position)
        assertEquals(position.chapterId, stored.positions.getValue(ref.key).chapterId)
        assertEquals("想读", stored.withReadingActivity(ref, oldToken, position, true, true).saved().status)
        val on = stored.withAutoReadingStatus(true)
        assertEquals("想读", on.withReadingActivity(ref, oldToken, position, true, true).saved().status)
        assertEquals("读完", on.read(end = true).saved().status)
        val manual = initial.withReadingStatus(setOf(ref.key), "想读").withAutoReadingStatus(false).withAutoReadingStatus(true)
        assertEquals("想读", manual.read(end = true).saved().status)
    }

    @Test fun restoringAutomaticManagementRejectsOldCallbacksAndDoesNotTrustStickyCompletion() {
        val initial = state().withReadingPosition(ref, position.copy(chapterCompleted = true))
        val oldToken = initial.readingStatusToken(ref)!!
        val manual = initial.withReadingStatus(setOf(ref.key), "想读")
        val restored = manual.withAutomaticReadingStatus(setOf(ref.key))
        assertEquals("在读", restored.saved().status)
        assertTrue(restored.saved().readingStatusControl!!.automatic)
        assertEquals("在读", restored.withReadingActivity(ref, oldToken, position, true, true).saved().status)
        assertEquals("读完", restored.read(end = true).saved().status)
        assertTrue(manual.saved().readingStatusControl!!.revision in restored.saved().readingStatusControl!!.supersedes)
    }

    @Test fun onlyNewChaptersDowngradeAutomaticCompletionAndManualFinishedStaysProtected() {
        val finished = state().read(end = true)
        val translationOnly = finished.withBookUpdate(book.copy(translated = 10), 100)
        assertEquals("读完", translationOnly.saved().status)
        val updated = finished.withBookUpdate(book.copy(total = 11), 100)
        assertEquals("在读", updated.saved().status)
        assertEquals("在读", updated.read(end = true).saved().status)
        assertEquals("读完", updated.read(position.copy(chapterId = "new", chapterIndex = 10, chapterCount = 11), end = true).saved().status)
        val manual = finished.withReadingStatus(setOf(ref.key), "读完").withBookUpdate(book.copy(total = 11), 100)
        assertEquals("读完", manual.saved().status)
        assertEquals("在读", finished.withSavedBook(book.copy(total = 11)).saved().status)
    }

    @Test fun batchSelectionAndRestoreDoNotChangePositionsOrUnselectedBooks() {
        val other = BookCard(BookRef("local", "other"), "另一本")
        val initial = state().withSavedBook(other).withReadingPosition(ref, position)
        val manual = initial.withReadingStatus(setOf(ref.key), "想读")
        assertEquals(initial.positions, manual.positions)
        assertEquals(initial.books.last(), manual.books.last())
        val automatic = manual.withAutomaticReadingStatus(manual.books.map { it.book.ref.key }.toSet())
        assertTrue(automatic.books.all { it.readingStatusControl!!.automatic })
        assertEquals(initial.positions, automatic.positions)
        assertEquals("在读", automatic.saved().status)
    }

    @Test fun restoringOtherBooksDoesNotResetAutomaticSpeechActivityWithoutAStoredPosition() {
        val reading = state().read()
        assertTrue(reading.positions.isEmpty())
        val legacy = SavedBook(BookCard(BookRef("local", "old-batch"), "旧状态"), status = "读完")
        val mixed = reading.copy(books = reading.books + legacy)
        val restored = mixed.withAutomaticReadingStatus(mixed.books.map { it.book.ref.key }.toSet())
        assertEquals(reading.saved(), restored.saved())
        assertTrue(restored.books.last().readingStatusControl!!.automatic)
    }

    @Test fun volumeActivityPromotesAutomaticParentButNeverInfersCompleteSeriesFromImportedSubset() {
        val parent = BookCard(BookRef("wenku", "series"), "文库作品", publishedVolumeCount = 5)
        val volume = BookCard(BookRef("local", "vol1"), "第一卷", total = 1)
        val initial = LibraryState().withSavedBook(parent).withSavedBook(volume).withVolumeParent(volume.ref.key, parent.ref.key)
        val token = initial.readingStatusToken(volume.ref)!!
        val point = position.copy(chapterIndex = 0, chapterCount = 1)
        val read = initial.withReadingActivity(volume.ref, token, point, true, true)
        assertEquals("读完", read.books.single { it.book.ref == volume.ref }.status)
        assertEquals("在读", read.books.single { it.book.ref == parent.ref }.status)
        val fixed = initial.withReadingStatus(setOf(parent.ref.key), "想读")
        assertEquals("想读", fixed.withReadingActivity(volume.ref, token, point, true, true).books.single { it.book.ref == parent.ref }.status)
        val childFixed = initial.withReadingStatus(setOf(volume.ref.key), "想读")
        val parentRead = childFixed.withReadingActivity(volume.ref, childFixed.readingStatusToken(volume.ref)!!, point, true, true)
        assertEquals("想读", parentRead.books.single { it.book.ref == volume.ref }.status)
        assertEquals("在读", parentRead.books.single { it.book.ref == parent.ref }.status)
    }

    @Test fun settingsAndManagementRoundTripWithoutChangingLegacyBackupDefaults() {
        assertTrue(Json.decodeFromString<SettingsBackup>("{}").autoReadingStatus)
        val backup = SettingsBackup(autoReadingStatus = false)
        assertFalse(Json.decodeFromString<SettingsBackup>(Json.encodeToString(backup)).autoReadingStatus)
        val manual = state().read(end = true).withReadingStatus(setOf(ref.key), "想读").withAutoReadingStatus(false)
        assertEquals(manual, json.decodeFromString<LibraryState>(json.encodeToString(manual)))
    }
}
