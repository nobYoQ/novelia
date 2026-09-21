package cc.novelia.app.ui.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.PageLine
import cc.novelia.app.reader.paragraphPartStarts
import cc.novelia.app.reader.prepareReadingParagraphs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EInkBilingualTest {
    @Test fun bothOrdersKeepShortPairsOnOnePageAndUseOneInternalLineBreak() {
        val chapter = Chapter(paragraphs = listOf("原文一", "原文二"), sakuraParagraphs = listOf("译文一", "译文二"))
        for(mode in listOf("zh-jp", "jp-zh")) {
            val settings = ReaderSettings(mode = mode, indent = false)
            val paragraphs = prepareReadingParagraphs(chapter, settings)
            val full = measureEInkChapter(paragraphs, settings, 327, 1000, 1f, 1f)
            val firstHeight = full.layouts.getValue(0).height
            val secondFirstLine = full.layouts.getValue(1).getLineBottom(0)
            val height = firstHeight + settings.resolvedParagraphSpacing.toInt() + secondFirstLine
            val measured = measureEInkChapter(paragraphs, settings, 327, height, 1f, 1f)
            assertEquals(2, measured.pages.size)
            measured.pages.forEachIndexed { index, page ->
                assertTrue(page.lines.all { it.paragraph == index })
                val layout = measured.layouts.getValue(index)
                assertEquals(paragraphs[index].parts.joinToString("\n") { it.text }, layout.text.toString())
                assertEquals(layout.text.length, page.lines.last().end)
                assertTrue(page.lines.sumOf(PageLine::height) <= height)
            }
        }
    }

    @Test fun changingLanguageOrderRestoresTheSourceParagraphInsteadOfAnOldConcatenatedOffset() {
        val chapter = Chapter(paragraphs = listOf("最初の文", "長い日本語。".repeat(20)), sakuraParagraphs = listOf("最初译文", "长篇中文。".repeat(24)))
        val state = EInkPageState(null)
        val settings = ReaderSettings(mode = "zh-jp", indent = true)
        val before = prepareReadingParagraphs(chapter, settings)
        val first = measureEInkChapter(before, settings, 327, 100, 1f, 1f)
        state.install(first.pages, before)
        state.find(1, paragraphPartStarts(before[1], settings)[1])
        assertTrue(state.textOffset > 0)

        val changed = settings.copy(mode = "jp-zh")
        val after = prepareReadingParagraphs(chapter, changed)
        val next = measureEInkChapter(after, changed, 327, 100, 1f, 1f)
        state.install(next.pages, after)
        assertTrue(state.current!!.lines.any { it.paragraph == 1 && it.start == 0 })
        assertEquals(1, after[state.current!!.lines.last().paragraph].index)
    }

    @Test fun minimumLineHeightShowsMoreCompleteLinesOnAShortWideScreen() {
        val chapter = Chapter(paragraphs = listOf("宽屏电子纸上的长篇正文。".repeat(200)))
        val settings = ReaderSettings(mode = "jp", fontSize = 19f, lineHeight = 1.3f)
        val paragraphs = prepareReadingParagraphs(chapter, settings)
        val previous = measureEInkChapter(paragraphs, settings, 690, 180, 1f, 1f)
        val compact = measureEInkChapter(paragraphs, settings.copy(lineHeight = 1f), 690, 180, 1f, 1f)
        assertTrue(compact.pages.first().lines.size > previous.pages.first().lines.size)
        assertTrue(compact.pages.size < previous.pages.size)
        assertTrue(compact.pages.all { it.lines.sumOf(PageLine::height) <= 180 })
        assertEquals(compact.layouts.getValue(0).text.length, compact.pages.last().lines.last().end)
    }
}
