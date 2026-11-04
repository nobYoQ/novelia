package cc.novelia.app.data.library

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.SyncDomain
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadingHistoryRemovalTest {
    private val local = BookRef("local", "book")
    private val web = BookRef("syosetu", "book")
    private val position = Position("chapter", index = 3, offset = 12, title = "第三章", updatedAt = 100)

    @Test fun removingOneBookKeepsOtherHistoryAndAllReadingDataAcrossReload() {
        val state = LibraryState(
            books = listOf(SavedBook(BookCard(local, "本地小说")), SavedBook(BookCard(web, "网络小说"))),
            positions = mapOf(local.key to position, web.key to position),
            notes = listOf(Note("note", local.key, position.chapterId, 3, "摘录", "笔记")),
        ).withMigratedReadingHistory()
        for(ref in listOf(local, web)) {
            val removed = state.withoutReadingHistory(ref)
            assertEquals(state.copy(readingHistory = state.readingHistory - ref.key), removed)
            assertEquals(setOf(SyncDomain.HISTORY), WebDavProjection.affectedLibraryDomains(state, removed))
            val reloaded = appJson.decodeFromString<LibraryState>(appJson.encodeToString(removed)).withMigratedReadingHistory()
            assertEquals(removed, reloaded)
            assertEquals(position, reloaded.positions[ref.key])
            assertEquals(removed, removed.withoutReadingHistory(ref))
        }
    }

    @Test fun deletingFromLegacyStateMigratesOtherBooksOnlyOnceAndNewReadingCanAddHistoryAgain() {
        val legacy = LibraryState(positions = mapOf(local.key to position, web.key to position))
        val removed = legacy.withoutReadingHistory(local)
        assertTrue(removed.historyMigrated)
        assertEquals(setOf(web.key), removed.withMigratedReadingHistory().readingHistory.keys)
        val newer = position.copy(index = 8, updatedAt = 200)
        assertFalse(removed.copy(historyPaused = true).withReadingPosition(local, newer).readingHistory.containsKey(local.key))
        assertEquals(200L, removed.withReadingPosition(local, newer).readingHistory.getValue(local.key).lastReadAt)
    }
}
