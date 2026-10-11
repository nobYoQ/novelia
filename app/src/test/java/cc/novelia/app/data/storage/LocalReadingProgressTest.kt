package cc.novelia.app.data.storage

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalReadingProgressTest {
    private val ref = BookRef("local", "volume")
    private val legacy = Position("second", index = 3, offset = 28, title = "第二章", updatedAt = 12345, textOffset = 7)
    private val current = LocalChapter("second", "第二章", listOf("", "正文", " ", "novelia-image:${"a".repeat(64)}", "<图片>invalid", "尾声"))
    private val catalogue = LocalDocument(ref.id, "第一卷", "epub", listOf(LocalChapter("first", "第一章", emptyList()), current.copy(paragraphs = emptyList())))
    private fun state(position: Position = legacy) = LibraryState(books = listOf(SavedBook(BookCard(ref, "第一卷"), parentWenkuKey = "wenku/series")), positions = mapOf(ref.key to position))

    @Test fun legacyVolumeUsesCatalogueAndOnlyItsCurrentChapterWhileKeepingTheAnchor() = runBlocking {
        val indexReads = mutableListOf<String>()
        val chapterReads = mutableListOf<Pair<String, String>>()
        val restored = resolveLocalReadingProgress(state().localReadingProgressCandidates(),
            { indexReads += it; catalogue }, { id, chapter -> chapterReads += id to chapter; current })
        assertEquals(listOf(ref.id), indexReads)
        assertEquals(listOf(ref.id to legacy.chapterId), chapterReads)
        val expected = legacy.copy(chapterIndex = 1, chapterCount = 2, paragraphCount = 4)
        assertEquals(expected, restored[ref])
        assertEquals(expected, state().withRestoredLocalReadingProgress(mapOf(ref to legacy), restored).positions[ref.key])
        assertEquals(12345L, restored.getValue(ref).updatedAt)
    }

    @Test fun scanSkipsRemoteUnreadAndAlreadyCompleteRecords() {
        val complete = legacy.copy(chapterIndex = 1, chapterCount = 2, paragraphCount = 4)
        val remote = BookRef("syosetu", "remote")
        val unread = BookRef("local", "unread")
        val orphan = BookRef("local", "removed")
        val source = state(complete).let { it.copy(books = it.books + listOf(SavedBook(BookCard(remote, "网络书")), SavedBook(BookCard(unread, "未读"))),
            positions = it.positions + mapOf(remote.key to legacy, orphan.key to legacy)) }
        assertTrue(source.localReadingProgressCandidates().isEmpty())
        assertEquals(mapOf(ref to legacy), source.copy(positions = source.positions + (ref.key to legacy)).localReadingProgressCandidates())
    }

    @Test fun missingAndDamagedDocumentsRemainUnknownWithoutBlockingOtherVolumes() = runBlocking {
        val missing = BookRef("local", "missing")
        val damaged = BookRef("local", "damaged")
        val candidates = linkedMapOf(missing to legacy, damaged to legacy, ref to legacy)
        val restored = resolveLocalReadingProgress(candidates,
            { if(it == missing.id) throw IOException("Missing document") else catalogue },
            { id, _ -> if(id == damaged.id) throw IOException("Damaged chapter") else current })
        assertEquals(setOf(ref), restored.keys)
        assertTrue(resolveLocalReadingProgress(mapOf(ref to legacy.copy(chapterId = "gone")), { catalogue },
            { _, _ -> error("A missing chapter must not fall back to the first chapter") }).isEmpty())
    }

    @Test fun cancellationIsPropagatedInsteadOfBeingTreatedAsMissingContent() = runBlocking {
        val cancellation = CancellationException("cancel loading")
        try {
            resolveLocalReadingProgress(mapOf(ref to legacy), { throw cancellation }, { _, _ -> current })
            fail("Cancellation must propagate")
        } catch(error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test fun delayedMetadataDoesNotOverwriteNewReadingOrRestoreRemovedRecords() {
        val restored = mapOf(ref to legacy.copy(chapterIndex = 1, chapterCount = 2, paragraphCount = 4))
        val candidates = mapOf(ref to legacy)
        val newer = state(legacy.copy(index = 4, updatedAt = legacy.updatedAt + 1))
        assertSame(newer, newer.withRestoredLocalReadingProgress(candidates, restored))
        val removedBook = state().copy(books = emptyList())
        assertSame(removedBook, removedBook.withRestoredLocalReadingProgress(candidates, restored))
        val clearedHistory = state().copy(positions = emptyMap())
        assertSame(clearedHistory, clearedHistory.withRestoredLocalReadingProgress(candidates, restored))
    }
}
