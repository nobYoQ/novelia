package cc.novelia.app.data.webdav

import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.catalog.KeywordLibraryFormat
import cc.novelia.app.data.library.withMigratedReadingHistory
import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.ReadingReturnPoint
import cc.novelia.app.reader.TextPart
import cc.novelia.app.reader.resolvedReadingPosition
import cc.novelia.app.reader.readingTextHash
import cc.novelia.app.ui.notes.presentNotes
import java.io.ByteArrayOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WebDavLibraryModelsTest {
    private val ref = BookRef("syosetu", "book")
    private val original = Position("c1", index = 3, title = "第一章", updatedAt = 100)

    @Test fun historyMigratesOnceAndClearingDoesNotDeleteOrRegenerateProgress() {
        val initial = LibraryState(books = listOf(SavedBook(BookCard(ref, "小说"))), positions = mapOf(ref.key to original))
        val migrated = initial.withMigratedReadingHistory()
        assertTrue(migrated.historyMigrated)
        assertEquals(ReadingHistoryEntry(ref.key, "小说", "c1", "第一章", 100), migrated.readingHistory[ref.key])
        val cleared = migrated.copy(readingHistory = emptyMap()).withMigratedReadingHistory()
        assertTrue(cleared.readingHistory.isEmpty())
        assertEquals(original, cleared.positions[ref.key])
    }

    @Test fun pausedHistoryStillSavesResumePositionWithoutChangingTheExistingHistory() {
        val initial = LibraryState(positions = mapOf(ref.key to original)).withMigratedReadingHistory().copy(historyPaused = true)
        val newer = original.copy(index = 5, updatedAt = 200)
        val updated = initial.withReadingPosition(ref, newer)
        assertEquals(newer, updated.positions[ref.key])
        assertEquals(initial.readingHistory, updated.readingHistory)
        val resumed = updated.copy(historyPaused = false).withReadingPosition(ref, newer.copy(updatedAt = 300))
        assertEquals(300L, resumed.readingHistory.getValue(ref.key).lastReadAt)
    }

    @Test fun historyForUnfavoritedBooksKeepsItsBookAndChapterTitlesThroughSerialization() {
        val state = LibraryState().withReadingPosition(ref, original, bookTitle = "未收藏的小说")
        assertTrue(state.books.isEmpty())
        val roundTrip = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        val entry = roundTrip.readingHistory.getValue(ref.key)
        assertEquals("未收藏的小说", entry.bookTitle)
        assertEquals("第一章", entry.chapterTitle)
        assertEquals(original, roundTrip.positions[ref.key])
    }

    @Test fun folderRenameKeepsIdentityAndMovesMountedLocalVolumesTogether() {
        val parent = SavedBook(BookCard(BookRef("wenku", "parent"), "文库"), folder = "旧收藏夹")
        val volume = SavedBook(BookCard(BookRef("local", "volume"), "分卷"), folder = "旧收藏夹", parentWenkuKey = parent.book.ref.key)
        val old = LibraryState(books = listOf(parent, volume), folders = listOf(DEFAULT_FOLDER, "旧收藏夹")).withStableFolderIds()
        val peer = LibraryState(folders = old.folders).withStableFolderIds()
        assertEquals(old.folderIds, peer.folderIds)
        val renamed = old.renameShelfFolder("旧收藏夹", "新收藏夹")
        assertEquals(old.folderIds["旧收藏夹"], renamed.folderIds["新收藏夹"])
        assertTrue(renamed.books.all { it.folder == "新收藏夹" && it.folderId == renamed.folderIds["新收藏夹"] })
        assertEquals(parent.book.ref.key, renamed.books.last().parentWenkuKey)
        val deleted = renamed.deleteShelfFolder("新收藏夹")
        assertTrue(deleted.books.all { it.folder == DEFAULT_FOLDER && it.folderId == DEFAULT_FOLDER_ID })
        assertEquals(parent.book.ref.key, deleted.books.last().parentWenkuKey)
    }

    @Test fun categoryRenamePreservesIdentityAndExportExcludesTheReplicaAndReadFailureMarker() {
        val library = KeywordLibrary(listOf(KeywordEntry("tag", category = "分类")), categories = listOf("其他", "分类", "空分类"))
        val peer = KeywordLibrary(emptyList(), categories = library.categories)
        assertEquals(library.categoryIds, peer.categoryIds)
        val renamed = library.renameCategory("分类", "改名")
        assertEquals(library.categoryIds["分类"], renamed.categoryIds["改名"])
        assertTrue("空分类" in renamed.categories)
        val flagged = renamed.copy(syncReadRecoveryRequired = true)
        val bytes = ByteArrayOutputStream().also { KeywordLibraryFormat.write(it, flagged) }.toByteArray()
        val text = bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("syncReplica"))
        assertFalse(text.contains("syncReadRecoveryRequired"))
        assertNotEquals(flagged.syncReplica.deviceId, KeywordLibraryFormat.decode(text).syncReplica.deviceId)
        assertEquals(flagged, KeywordLibraryFormat.decodeLocal(appJson.encodeToString(flagged)))
    }

    @Test fun crossDeviceAnchorsUseOriginalParagraphAndDifferentLanguagesResetCharacterOffsets() {
        val settings = ReaderSettings(mode = "zh", engines = listOf("sakura"))
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("甲", "sakura"))), ReadingParagraph(4, listOf(TextPart("乙", "sakura"))))
        val synced = Position("c", index = 5, offset = 90, textOffset = 8, sourceParagraph = 4,
            anchorSource = WebDavProjection.anchorSource(settings))
        val restored = synced.resolvedReadingPosition(paragraphs, settings)
        assertEquals(2, restored.index)
        assertEquals(8, restored.textOffset)
        val translated = synced.resolvedReadingPosition(paragraphs, settings.copy(mode = "jp"))
        assertEquals(2, translated.index)
        assertEquals(0, translated.textOffset)
        assertEquals(0, translated.offset)
    }

    @Test fun readingSessionRoundTripRetainsOriginalSourceAndRejectsDifferentIndentOffsets() {
        val settings = ReaderSettings(indent = true)
        val paragraphs = listOf(ReadingParagraph(1, listOf(TextPart("首段", "sakura"))), ReadingParagraph(8, listOf(TextPart("目标", "sakura"))))
        val point = ReadingReturnPoint(Position("c", index = 9, textOffset = 7, sourceParagraph = 8,
            anchorSource = WebDavProjection.anchorSource(settings)), sourceIndex = 8)
        val restored = appJson.decodeFromString<ReadingReturnPoint>(appJson.encodeToString(point))
        assertEquals(2, restored.resolvedPosition(paragraphs, settings).index)
        assertEquals(7, restored.resolvedPosition(paragraphs, settings).textOffset)
        assertEquals(0, restored.resolvedPosition(paragraphs, settings.copy(indent = false)).textOffset)
    }

    @Test fun changedTranslationTextKeepsTheOriginalParagraphButDropsStaleOffsets() {
        val settings = ReaderSettings(engines = listOf("sakura"))
        val first = ReadingParagraph(2, listOf(TextPart("相同首段", "sakura")))
        val originalText = ReadingParagraph(7, listOf(TextPart("用户上次阅读的旧译文正文", "sakura")))
        val position = Position("chapter", index = 2, offset = 50, textOffset = 9, sourceParagraph = 7,
            anchorSource = WebDavProjection.anchorSource(settings), anchorTextHash = originalText.readingTextHash())
        val restored = appJson.decodeFromString<Position>(appJson.encodeToString(position))
        assertEquals(position, restored.resolvedReadingPosition(listOf(first, originalText), settings))
        val changed = originalText.copy(parts = listOf(TextPart("同一个译源提供的新译文", "sakura")))
        val fallback = restored.resolvedReadingPosition(listOf(first, changed), settings)
        assertEquals(2, fallback.index)
        assertEquals(7, fallback.sourceParagraph)
        assertEquals(0, fallback.offset)
        assertEquals(0, fallback.textOffset)
        val exported = WebDavProjection.library(LibraryState(positions = mapOf(ref.key to position))).toString()
        assertTrue(exported.contains(position.anchorTextHash!!))
        assertFalse(exported.contains("用户上次阅读的旧译文正文"))
    }

    @Test fun emptyChapterAndMissingSourceParagraphSafelyFallbackWithoutReusingTextOffsets() {
        val settings = ReaderSettings()
        val original = ReadingParagraph(9, listOf(TextPart("旧段落", "sakura")))
        val position = Position("chapter", index = 3, offset = 50, textOffset = 9, sourceParagraph = 9,
            anchorSource = WebDavProjection.anchorSource(settings), anchorTextHash = original.readingTextHash())
        val empty = position.resolvedReadingPosition(emptyList(), settings)
        assertEquals(0, empty.index)
        assertEquals(0, empty.textOffset)
        val removed = position.resolvedReadingPosition(listOf(original.copy(index = 4)), settings)
        assertEquals(1, removed.index)
        assertEquals(0, removed.textOffset)
    }

    @Test fun noteBodyAndBookmarkCanExistIndependentlyWithoutShowingEmptyGhostRecords() {
        val base = Note("bookmark", ref.key, "c", 0, "摘录", "")
        val bodyOnly = base.copy(id = "note", bookmarked = false, text = "笔记正文")
        val ghost = base.copy(id = "ghost", bookmarked = false)
        val presented = presentNotes(LibraryState(notes = listOf(base, bodyOnly, ghost)))
        assertEquals(setOf("bookmark", "note"), presented.map { it.note.id }.toSet())
    }
}
