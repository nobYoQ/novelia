package cc.novelia.app

import cc.novelia.app.reader.*
import org.junit.Assert.*
import org.junit.Test

class StaticPaginationTest {
    @Test fun longParagraphSplitsWithoutMissingOrRepeatingLines() {
        val lines = (0 until 103).map { PageLine(0, it * 8, (it + 1) * 8, 32) }
        val pages = paginateLines(lines, 200, 20)
        assertEquals(lines, pages.flatMap { it.lines })
        assertTrue(pages.all { it.lines.sumOf(PageLine::height) <= 200 })
        assertEquals(18, pages.size)
        assertEquals(5, pageForAnchor(pages, 0, 240))
    }

    @Test fun paragraphSpacingIsCountedAndImagesHaveTheirOwnPage() {
        val lines = listOf(PageLine(0, 0, 3, 45), PageLine(1, 0, 3, 45), PageLine(2, 0, 0, 1000, true), PageLine(3, 0, 3, 45))
        val pages = paginateLines(lines, 100, 20)
        assertEquals(4, pages.size)
        assertEquals(lines, pages.map { it.lines.single() })
        assertEquals(2, pageForAnchor(pages, 2, 0))
    }

    @Test fun reflowKeepsThePageContainingTheSameCharacter() {
        val old = paginateLines((0..20).map { PageLine(1, it * 12, (it + 1) * 12, 20) }, 100, 0)
        val anchor = old[2].lines.first()
        val reflow = paginateLines((0..41).map { PageLine(1, it * 6, (it + 1) * 6, 28) }, 90, 0)
        val target = reflow[pageForAnchor(reflow, anchor.paragraph, anchor.start)]
        assertTrue(target.lines.any { anchor.start in it.start until it.end })
    }

    @Test fun emptyAndVeryShortViewportsStillMakeProgress() {
        assertEquals(1, paginateLines(emptyList(), 1, 0).size)
        val lines = listOf(PageLine(0, 0, 8, 50), PageLine(0, 8, 16, 50))
        assertEquals(2, paginateLines(lines, 10, 0).size)
    }

    @Test fun physicalKeyMappingRespectsVolumePreference() {
        listOf(92, 19, 21).forEach { assertEquals(-1, readerKeyDirection(it, false)) }
        listOf(93, 20, 22, 62).forEach { assertEquals(1, readerKeyDirection(it, false)) }
        assertEquals(0, readerKeyDirection(24, false))
        assertEquals(-1, readerKeyDirection(24, true))
        assertEquals(1, readerKeyDirection(25, true))
        assertEquals(0, readerKeyDirection(4, true))
    }
}
