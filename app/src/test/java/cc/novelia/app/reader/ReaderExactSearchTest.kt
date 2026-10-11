package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.TocItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderExactSearchTest {
    @Test fun firstSearchStartsAtVisibleCharacterAndSelectedSearchWrapsBothWays() {
        val matches = listOf(ReadingTextMatch(0, 0, 10, 12), ReadingTextMatch(0, 0, 600, 602), ReadingTextMatch(1, 0, 0, 2))
        fun next(current: ReadingTextMatch?, direction: Int, paragraph: Int, offset: Int) =
            nextReadingMatchIndex(matches, current, direction, paragraph, offset) { it.start }
        assertEquals(1, next(null, 1, 0, 500))
        assertEquals(0, next(null, -1, 0, 500))
        assertEquals(0, next(matches.last(), 1, 1, 0))
        assertEquals(2, next(matches.first(), -1, 0, 10))
        assertEquals(0, next(null, 1, 1, 100))
        assertEquals(-1, nextReadingMatchIndex(emptyList(), null, 1, 0, 0) { it.start })
    }

    @Test fun findsEveryOccurrenceInLongParagraphAndEveryLanguagePart() {
        val paragraphs = listOf(ReadingParagraph(4, listOf(
            TextPart("星" + "甲".repeat(600) + "星", "gpt"), TextPart("星と星", "日文", true))))
        val matches = findReadingTextMatches(paragraphs, " 星 ")
        assertEquals(listOf(0, 601, 0, 2), matches.map { it.start })
        assertEquals(listOf(0, 0, 1, 1), matches.map { it.part })
        assertEquals(listOf(0, 0, 0, 0), matches.map { it.paragraph })
    }

    @Test fun exactAnchorIncludesTranslatedLabelAndIndentAndSelectsTheLatePage() {
        val settings = ReaderSettings(parallel = true, indent = true)
        val paragraph = ReadingParagraph(9, listOf(TextPart("甲".repeat(600) + "目标", "gpt")))
        val match = findReadingTextMatches(listOf(paragraph), "目标").single()
        val offset = match.textOffset(paragraph, settings)
        assertEquals(606, offset)
        val pages = paginateLines((0..30).map { PageLine(0, it * 20, (it + 1) * 20, 20) }, 100, 0)
        assertEquals(6, pageForAnchor(pages, match.paragraph, offset))
        val scroll = ParagraphScrollLayout(mapOf(0 to (0..30).map { ReadingAnchorLine(it * 20, (it + 1) * 20, it * 24) }))
        assertEquals(720, scroll.scrollOffsetAt(offset))
    }

    @Test fun bookSearchKeepsPreciseRangesAndStopsInsideSingleLargeParagraph() = runBlocking {
        val result = searchBookText(listOf(TocItem("章", chapterId = "one")), "aa", ReaderSettings(mode = "jp"),
            load = { Chapter(paragraphs = listOf("aaaaa")) }, maxResults = 2)
        assertEquals(listOf(0, 1), result.matches.map { it.start })
        assertEquals(listOf(2, 3), result.matches.map { it.end })
        assertTrue(result.truncated)
    }

    @Test fun conversionAndSearchCanBeCancelledWithinOneHugeParagraph() {
        var checks = 0
        try {
            prepareReadingParagraphs(Chapter(paragraphs = listOf("原文"), youdaoParagraphs = listOf("阅读".repeat(20_000))), ReaderSettings(traditional = true)) {
                if(++checks == 8) throw CancellationException()
            }
            fail("Expected cancellation during conversion")
        } catch(_: CancellationException) { assertEquals(8, checks) }
        checks = 0
        try {
            findReadingTextMatches(listOf(ReadingParagraph(0, listOf(TextPart("甲".repeat(20_000), "日文")))), "甲") {
                if(++checks == 8) throw CancellationException()
            }
            fail("Expected cancellation during search")
        } catch(_: CancellationException) { assertEquals(8, checks) }
    }

    @Test fun speechSplitsAtSentencesAndKeepsClosingQuotesAndAllCharacters() {
        val text = "他说：“第一句。”第二句！3.14 is a number. 下一句？"
        val queue = prepareSpeechQueue(listOf(text))
        assertEquals(text, queue.joinToString(""))
        assertEquals("他说：“第一句。”", queue.first())
        assertTrue(queue.any { it.contains("3.14") })
        assertEquals(4, queue.size)
    }
}
