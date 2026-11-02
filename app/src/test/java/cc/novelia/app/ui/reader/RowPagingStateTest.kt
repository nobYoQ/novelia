package cc.novelia.app.ui.reader

import cc.novelia.app.data.model.Position
import cc.novelia.app.reader.*
import org.junit.Assert.*
import org.junit.Test

class RowPagingStateTest {
    private val content = listOf(ReadingParagraph(7, listOf(TextPart("正文".repeat(300), "local"))))
    private fun lines(count: Int = 25, height: Int = 20, chars: Int = 10) = List(count) { PageLine(0, it * chars, (it + 1) * chars, height) }
    private fun state(count: Int = 25) = RowPagingState(null).apply {
        install(paginateLines(lines(count), 200, 0), content, 200, 0)
    }

    @Test fun scrollingThenPagingUsesTheActualScreenAndReturnsToItsStart() {
        val state = state()
        state.beginScroll()
        state.scrollBy(60f)
        state.finishScroll()
        assertEquals(3, state.row)
        assertEquals(30, state.anchor.textOffset)
        state.move(1)
        assertEquals(13, state.row)
        state.move(-1)
        assertEquals(3, state.row)
        repeat(4) { state.move(1); state.move(-1); assertEquals(3, state.row) }
    }

    @Test fun restoringAfterVerticalScrollDoesNotRoundToAnOldPageBoundary() {
        val original = state()
        original.beginScroll()
        original.scrollBy(120f)
        original.finishScroll()
        assertEquals(60, original.anchor.textOffset)
        val reopened = RowPagingState(Position("chapter", original.anchor.paragraph + 1,
            textOffset = original.anchor.textOffset, sourceParagraph = original.anchor.sourceIndex))
        reopened.install(original.pages, content, 200, 0)
        assertEquals(6, reopened.row)
        assertEquals(original.viewportTop, reopened.viewportTop, 0f)
    }

    @Test fun shortFinalScreenDoesNotGetClampedBackIntoPreviousContent() {
        val state = state()
        state.move(1)
        state.move(1)
        assertEquals(20, state.row)
        assertEquals(400f, state.viewportTop, 0f)
        assertEquals(200, state.layout!!.slice(state.viewportTop, false).lines.first().start)
        assertEquals(5, state.layout!!.slice(state.viewportTop, false).lines.size)
        assertFalse(state.canGoForward)
        state.move(-1)
        assertEquals(10, state.row)
    }

    @Test fun externalFindAndSeekMoveTheVisibleRowsAsWellAsTheProgress() {
        val state = state(100)
        state.find(0, 475, 7)
        assertEquals(47, state.row)
        assertEquals(475, state.anchor.textOffset)
        state.seekPage(8)
        assertEquals(80, state.row)
        assertEquals(800, state.anchor.textOffset)
        state.move(1)
        assertEquals(90, state.row)
    }

    @Test fun repeatedReflowPreservesTheRequestedCharacterUntilTheUserMoves() {
        val state = state()
        state.find(0, 47, 7)
        repeat(4) {
            val next = lines(50, chars = if(it % 2 == 0) 6 else 10)
            state.install(paginateLines(next, 150, 12), content, 150, 12)
            assertEquals(47, state.anchor.textOffset)
            val visible = state.layout!!.lines[state.row]
            assertTrue(47 in visible.start until visible.end)
        }
    }

    @Test fun chapterEndSentinelLandsAtTheLastScreenAndEmptyChaptersRemainUsable() {
        val state = RowPagingState(Position("chapter", Int.MAX_VALUE))
        state.install(paginateLines(lines(), 200, 0), content, 200, 0)
        assertEquals(20, state.row)
        assertFalse(state.canGoForward)
        state.install(listOf(StaticPage(emptyList())), emptyList(), 200, 0)
        assertTrue(state.ready)
        assertEquals(1, state.pages.size)
        assertFalse(state.canGoBack || state.canGoForward)
        state.move(1)
    }

    @Test fun manualScrollingClearsBackHistoryAndSavingUsesSourceParagraph() {
        val state = state(100)
        state.move(1)
        state.move(1)
        state.beginScroll()
        state.scrollBy(140f)
        state.finishScroll()
        assertEquals(27, state.row)
        state.move(-1)
        assertEquals(17, state.row)
        assertEquals(7, state.anchor.sourceIndex)
        assertEquals(170, state.anchor.textOffset)
    }

    @Test fun titleAndImageRowsAreKeptAndImagesOccupyTheirOwnScreen() {
        val rows = listOf(PageLine(-1, 0, 6, 30), PageLine(0, 0, 10, 20), PageLine(1, 0, 0, 200, true), PageLine(2, 0, 10, 20))
        val geometry = RowPaginationLayout(rows, 200, 8)
        assertEquals(listOf(0, 2, 3), geometry.pageStarts)
        assertTrue(geometry.pages[1].lines.single().image)
        assertEquals(rows, geometry.pages.flatMap { it.lines })
        assertEquals(2, geometry.previousStart(3))
        val flowing = geometry.slice(50f, true)
        assertTrue(flowing.lines.any { it.image })
    }

    @Test fun variableHeightsAndParagraphGapsNeverDropOrDuplicateRows() {
        val rows = List(107) { PageLine(it / 3, it % 3 * 10, it % 3 * 10 + 10, 20 + it % 7 * 5) }
        val geometry = RowPaginationLayout(rows, 200, 11)
        assertEquals(rows, geometry.pages.flatMap { it.lines })
        assertTrue(geometry.pages.all { page -> page.lines.sumOf { it.height } +
            page.lines.zipWithNext().count { (a, b) -> a.paragraph != b.paragraph } * 11 <= 200 })
    }

    @Test fun oversizedRowsAndSmallViewportsStillMakeProgress() {
        val geometry = RowPaginationLayout(lines(3, height = 100), 10, 0)
        assertEquals(3, geometry.pages.size)
        assertEquals(20f, geometry.maxScrollTop, 0f)
        assertEquals(1, geometry.nearestRow(6f))
    }
}
