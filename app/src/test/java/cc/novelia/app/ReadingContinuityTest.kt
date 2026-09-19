package cc.novelia.app

import cc.novelia.app.data.*
import cc.novelia.app.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadingContinuityTest {
    private val toc = listOf(TocItem("第一卷"), TocItem("开端", chapterId = "a"), TocItem("第二卷"), TocItem("帰り道", "归途", "b"), TocItem("尾声", chapterId = "c"))

    @Test fun resumeUsesReadableNumberAndTranslatedTitleWithoutCountingSectionHeadings() {
        assertEquals("第 2 章 · 归途", readingDestination(toc, "b")?.label)
        assertNull(readingDestination(toc, "removed"))
        assertEquals("b", resumeDestination(toc, "removed", "b")?.chapterId)
        assertEquals("a", resumeDestination(toc, "removed", "missing")?.chapterId)
        assertNull(resumeDestination(emptyList(), "a", "b"))
    }

    @Test fun cacheRangesFollowFullDirectoryAndRejectOversizedOrInvalidBatches() {
        assertEquals(listOf("b", "c"), chapterCacheRange(toc, 2, 3))
        assertTrue(runCatching { chapterCacheRange(toc, 0, 3) }.isFailure)
        assertTrue(runCatching { chapterCacheRange(toc, 2, 1) }.isFailure)
        assertTrue(runCatching { chapterCacheRange(List(201) { TocItem(chapterId = "$it") }, 1, 201) }.isFailure)
        assertEquals("已缓存 2/3 章 · 第 1、3 章", offlineRangeLabel(toc, setOf("a", "c")))
        assertEquals("已缓存 3/3 章 · 第 1–3 章", offlineRangeLabel(toc, setOf("a", "b", "c")))
    }

    @Test fun nextMountedVolumeFollowsSavedSiblingOrderAndStaysInsideParent() {
        val parent = SavedBook(BookCard(BookRef("wenku", "parent"), "父书"), volumeOrder = listOf("local/c", "local/a", "local/b"))
        val siblings = listOf("a", "b", "c").map { SavedBook(BookCard(BookRef("local", it), "第 $it 卷"), parentWenkuKey = parent.book.ref.key) }
        val state = LibraryState(books = listOf(parent) + siblings + SavedBook(BookCard(BookRef("local", "unmounted"), "未挂载")))
        assertEquals(BookRef("local", "b"), state.nextMountedVolume(BookRef("local", "a"))?.book?.ref)
        assertNull(state.nextMountedVolume(BookRef("local", "b")))
        assertNull(state.nextMountedVolume(BookRef("local", "unmounted")))
        assertNull(state.withoutBook(parent.book.ref).nextMountedVolume(BookRef("local", "a")))
    }

    @Test fun searchOnlyReadsProvidedChaptersAndReturnsProjectedParagraphOffsets() = runBlocking {
        val chapter = Chapter(paragraphs = listOf("", "原文", "<图片>https://example.com/illustration.jpg", "后文"), youdaoParagraphs = listOf("", "寻找星星", "", "尾声的星星"))
        val result = searchBookText(toc, "星星", ReaderSettings(), load = { if(it == "b") chapter else null })
        assertEquals(1, result.availableChapters)
        assertEquals(1, result.scannedChapters)
        assertEquals(3, result.totalChapters)
        assertEquals(listOf(0, 2), result.matches.map { it.paragraph })
        assertEquals("第 2 章 · 归途", result.matches.first().chapterLabel)
        assertEquals("寻找星星", result.matches.first().snippet)
        assertFalse(result.truncated)
    }

    @Test fun searchStopsLoadingMoreChaptersAtResultLimit() = runBlocking {
        var reads = 0
        val result = searchBookText(toc, "match", ReaderSettings(mode = "jp"), load = { reads++; Chapter(paragraphs = listOf("match one", "match two", "match three")) }, maxResults = 2)
        assertTrue(result.truncated)
        assertEquals(2, result.matches.size)
        assertEquals(1, reads)
    }

    @Test fun searchHonorsTextBudgetAndPropagatesCancellation() = runBlocking {
        val result = searchBookText(toc, "星", ReaderSettings(mode = "jp"), load = { Chapter(paragraphs = listOf("星".repeat(100))) }, maxCharacters = 10)
        assertTrue(result.truncated)
        assertTrue(result.matches.isEmpty())
        var cancelled = false
        try { searchBookText(toc, "星", ReaderSettings(), load = { throw CancellationException() }) }
        catch(_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
