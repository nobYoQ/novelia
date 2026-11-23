package cc.novelia.app.ui.reader

import androidx.compose.ui.unit.IntRect
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import org.junit.Assert.*
import org.junit.Test

class ReaderSpreadTest {
    private val paragraphs = listOf(ReadingParagraph(7, listOf(TextPart("正文".repeat(500), "local"))))
    private fun pages(count: Int) = List(count) { StaticPage(listOf(PageLine(0, it * 100, (it + 1) * 100, 20))) }

    @Test fun onlyTheHorizontalSubmodeUsesWideWindows() {
        val enabled = ReaderSettings(paginationMode = "auto", horizontalDualPage = true)
        assertFalse(readerUsesDualPage(ReaderSettings(paginationMode = "auto"), 1024f))
        assertFalse(readerUsesDualPage(enabled, 719f))
        assertTrue(readerUsesDualPage(enabled, 720f))
        for(mode in listOf("scroll", "row")) assertFalse(readerUsesDualPage(enabled.withPaginationMode(mode), 1200f))
    }

    @Test fun anOffCenterHingeUsesWindowCoordinatesAndTheNarrowerPanel() {
        val viewport = IntRect(24, 48, 824, 468)
        val hinge = IntRect(334, 0, 370, 500)
        val spread = readerSpreadLayout(viewport, listOf(hinge), maxPageWidth = 720, gutterWidth = 16, pageInset = 24)
        assertEquals(ReaderSpreadLayout(leftPageX = 24, rightPageX = 442, pageWidth = 262, dividerX = null), spread)
    }

    @Test fun aZeroWidthFoldGetsClearanceOnBothSides() {
        val viewport = IntRect(24, 48, 824, 468)
        val fold = IntRect(424, 0, 424, 500)
        val spread = readerSpreadLayout(viewport, listOf(fold), maxPageWidth = 720, gutterWidth = 16, pageInset = 0)
        assertEquals(ReaderSpreadLayout(leftPageX = 0, rightPageX = 408, pageWidth = 392, dividerX = null), spread)
    }

    @Test fun foldsOutsideTheViewportLeaveTheCenteredSpreadAndWidthLimit() {
        val viewport = IntRect(24, 48, 1524, 468)
        val folds = listOf(IntRect(760, 0, 788, 48), IntRect(0, 0, 24, 500))
        val spread = readerSpreadLayout(viewport, folds, maxPageWidth = 500, gutterWidth = 16, pageInset = 24)
        assertEquals(ReaderSpreadLayout(leftPageX = 145, rightPageX = 903, pageWidth = 452, dividerX = 750), spread)
    }

    @Test fun forwardAndBackwardTurnTwoPagesIncludingAnOddFinalPage() {
        val state = EInkPageState(null).apply { install(pages(5), paragraphs, 2) }
        assertEquals(2, state.visiblePageCount)
        state.move(1)
        assertEquals(2, state.pageIndex)
        state.move(1)
        assertEquals(4, state.pageIndex)
        assertFalse(state.canGoForward)
        state.move(1)
        assertEquals(4, state.pageIndex)
        state.move(-1)
        assertEquals(2, state.pageIndex)
        state.move(-1)
        assertEquals(0, state.pageIndex)
        assertFalse(state.canGoBack)
    }

    @Test fun evenFinalSpreadDoesNotTurnIntoAnOverlappingExtraPage() {
        val state = EInkPageState(null).apply { install(pages(4), paragraphs, 2) }
        state.move(1)
        assertEquals(2, state.pageIndex)
        assertFalse(state.canGoForward)
        state.seekPage(3)
        assertEquals(2, state.pageIndex)
    }

    @Test fun seekingUsesAbsolutePagesAndSearchingTheRightPagePreservesItsCharacterAnchor() {
        val state = EInkPageState(null).apply { install(pages(8), paragraphs, 2) }
        state.seekPage(5)
        assertEquals(4, state.pageIndex)
        state.seekPage(7)
        assertEquals(6, state.pageIndex)
        state.find(0, 350, 7)
        assertEquals(2, state.pageIndex)
        assertEquals(350, state.anchor.textOffset)
        state.install(pages(8), paragraphs, 1)
        assertEquals(3, state.pageIndex)
        assertEquals(350, state.anchor.textOffset)
        state.install(pages(8), paragraphs, 2)
        assertEquals(2, state.pageIndex)
        assertEquals(350, state.anchor.textOffset)
    }

    @Test fun aSavedRightPageAndPreviousChapterEndRestoreToTheContainingSpread() {
        val rightPage = EInkPageState(Position("chapter", 1, textOffset = 350)).apply { install(pages(8), paragraphs, 2) }
        assertEquals(2, rightPage.pageIndex)
        assertEquals(350, rightPage.anchor.textOffset)
        for(count in listOf(1, 4, 5)) {
            val end = EInkPageState(Position("chapter", Int.MAX_VALUE)).apply { install(pages(count), paragraphs, 2) }
            assertEquals((count - 1) / 2 * 2, end.pageIndex)
            assertFalse(end.canGoForward)
        }
    }

    @Test fun invalidatedLayoutDoesNotAcceptTurnsOrSeeks() {
        val state = EInkPageState(null).apply { install(pages(6), paragraphs, 2) }
        state.invalidate()
        state.move(1)
        state.seekPage(4)
        assertEquals(0, state.pageIndex)
        state.confirm(state.pages)
        state.move(1)
        assertEquals(2, state.pageIndex)
    }
}
