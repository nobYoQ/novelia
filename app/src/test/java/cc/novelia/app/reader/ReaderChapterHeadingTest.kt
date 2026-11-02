package cc.novelia.app.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderChapterHeadingTest {
    @Test fun addsMissingHeadingWithoutChangingBodyIdentity() {
        val body = listOf(ReadingParagraph(7, listOf(TextPart("章节一的故事开始了。", "gpt"))))
        assertEquals("章节一", chapterHeading(" 章节一 ", body))
        assertEquals(7, body.single().index)
        assertEquals("章节一", chapterHeading("章节一", emptyList()))
        assertNull(chapterHeading("　 ", body))
    }

    @Test fun onlyAnEntireLeadingTitleSuppressesTheExtraHeading() {
        val title = ReadingParagraph(0, listOf(TextPart("第一章　\n启程", "local")))
        assertNull(chapterHeading("第一章 启程", listOf(title)))
        assertNull(chapterHeading("第一章 启程", listOf(title.copy(parts = listOf(TextPart("出発", "jp")) + title.parts))))
        val body = ReadingParagraph(1, listOf(TextPart("我们翻开了第一章 启程。", "local")))
        assertEquals("第一章 启程", chapterHeading("第一章 启程", listOf(body, title)))
        assertEquals("第一章 启程", chapterHeading("第一章 启程", listOf(ReadingParagraph(0, emptyList(), localImageId = "cover"), title)))
    }

    @Test fun headingAndBodyAnchorsRemainSeparateAcrossPages() {
        val lines = listOf(PageLine(-1, 0, 10, 40), PageLine(-1, 10, 20, 40),
            PageLine(0, 0, 10, 40), PageLine(0, 10, 20, 40), PageLine(1, 0, 10, 40))
        val pages = paginateLines(lines, 70, 10)
        assertEquals(lines, pages.flatMap { it.lines })
        assertEquals(0, pageForAnchor(pages, -1, 0))
        assertEquals(1, pageForAnchor(pages, -1, 12))
        assertEquals(3, pageForAnchor(pages, 0, 12))
        assertEquals(4, pageForAnchor(pages, 1, 0))
    }
}
