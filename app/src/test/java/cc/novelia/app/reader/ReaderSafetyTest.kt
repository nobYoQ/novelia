package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReaderSafetyTest {
    @Test fun illustratedAndBlankChaptersHaveNoSpeechQueue() {
        assertTrue(prepareSpeechQueue(listOf(" ", "\n", "<图片>https://example.com/a.png", "novelia-image:" + "a".repeat(64))).isEmpty())
        assertEquals(listOf("可朗读的文字"), prepareSpeechQueue(listOf(" 可朗读的文字 ", "\n")))
    }

    @Test fun speechChunksKeepAllTextAndSurrogatePairs() {
        val original = "文".repeat(3499) + "🌳" + "字".repeat(4100)
        val queue = prepareSpeechQueue(listOf(original))
        assertEquals(original, queue.joinToString(""))
        assertTrue(queue.all { it.length <= 3500 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test fun preparingLongSpeechCanBeCancelledBetweenChunks() {
        var checks = 0
        try {
            prepareSpeechQueue(listOf("正文".repeat(20_000))) { if(++checks == 3) throw CancellationException() }
            fail("Expected cancellation")
        } catch(_: CancellationException) { assertEquals(3, checks) }
    }

    @Test fun scrollAndStaticOffsetsIncludeIdenticalParallelLabelsAndIndent() {
        val paragraph = ReadingParagraph(7, listOf(TextPart("甲乙", "gpt"), TextPart("日本語", "日文", true)))
        val starts = paragraphPartStarts(paragraph, ReaderSettings(parallel = true, indent = true))
        assertEquals(listOf(4, 9), starts)
        val scroll = ParagraphScrollLayout(mapOf(
            0 to listOf(ReadingAnchorLine(starts[0], starts[0] + 2, 20), ReadingAnchorLine(starts[0] + 2, starts[0] + 4, 40)),
            1 to listOf(ReadingAnchorLine(starts[1], starts[1] + 2, 70), ReadingAnchorLine(starts[1] + 2, starts[1] + 5, 90))))
        val pages = listOf(
            StaticPage(listOf(PageLine(0, starts[0], starts[0] + 2, 20))),
            StaticPage(listOf(PageLine(0, starts[0] + 2, starts[0] + 4, 20))),
            StaticPage(listOf(PageLine(0, starts[1], starts[1] + 2, 20))),
            StaticPage(listOf(PageLine(0, starts[1] + 2, starts[1] + 5, 20))))
        val character = scroll.textOffsetAt(95)
        assertEquals(11, character)
        val page = pages[pageForAnchor(pages, 0, character)]
        assertEquals(90, scroll.scrollOffsetAt(page.lines.first().start))
    }

    @Test fun paginationCanBeCancelledWhilePackingLongChapters() {
        var checks = 0
        try {
            paginateLines(List(1000) { PageLine(0, it, it + 1, 20) }, 300, 20) {
                if(++checks == 12) throw CancellationException()
            }
            fail("Expected cancellation")
        } catch(_: CancellationException) { assertEquals(12, checks) }
    }
}
