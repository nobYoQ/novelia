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

    @Test fun settlingMovesContinuouslyAndCannotOverwriteAnExternalFind() {
        val state = state()
        state.beginScroll()
        state.scrollBy(53.5f)
        assertEquals(60f, state.scrollTarget, 0f)
        state.settleScroll(56.25f)
        assertTrue(state.scrolling)
        assertEquals(56.25f, state.viewportTop, 0f)
        state.find(0, 175, 7)
        state.settleScroll(60f)
        state.scrollBy(10f)
        assertEquals(340f, state.viewportTop, 0f)
        assertEquals(175, state.anchor.textOffset)
    }

    @Test fun visibleStripKeepsThePartiallyVisibleBottomRowWhilePageTurnsRepeatItInFull() {
        val geometry = RowPaginationLayout(lines(30, height = 30), 200, 0)
        assertEquals(6, geometry.screenEnd(0))
        val visible = geometry.slice(0f, true)
        assertEquals(7, visible.lines.size)
        assertEquals(60, visible.lines.last().start)
        assertEquals(60, geometry.pages[1].lines.first().start)
    }

    @Test fun chapterFooterScrollsIntoViewWithoutAddingPagesOrLosingTheTextAnchor() {
        val state = state()
        val pages = state.pages
        state.updateEndContentHeight(173)
        state.seekPage(pages.lastIndex)
        assertFalse(state.canGoForward)
        assertTrue(state.canScrollForward)
        state.beginScroll()
        assertEquals(73f, state.scrollBy(200f), 0f)
        assertFalse(state.canScrollForward)
        state.finishScroll()
        assertEquals(473f, state.viewportTop, 0f)
        assertFalse(state.canScrollForward)
        assertEquals(pages, state.pages)
        assertEquals(7, state.anchor.sourceIndex)
        assertEquals(230, state.anchor.textOffset)
        state.beginScroll()
        state.scrollBy(-100f)
        state.finishScroll()
        assertEquals(380f, state.viewportTop, 0f)
        assertTrue(state.canScrollForward)
    }

    @Test fun oversizedFooterAndShortChaptersStillReachAnEndWithoutInventingTextRows() {
        val state = state(2)
        state.updateEndContentHeight(100)
        assertFalse(state.canScrollForward)
        state.updateEndContentHeight(400)
        assertTrue(state.canScrollForward)
        state.beginScroll()
        assertEquals(240f, state.scrollBy(1000f), 0f)
        state.settleScroll(state.scrollTarget)
        state.finishScroll()
        assertEquals(240f, state.viewportTop, 0f)
        assertFalse(state.canScrollForward)
        assertEquals(1, state.pages.size)
        assertEquals(10, state.anchor.textOffset)
        state.seekPage(0)
        assertEquals(0f, state.viewportTop, 0f)
    }

    @Test fun verticalEndDoesNotLeaveTheUnusedPartOfTheLastPageBelowTheFooter() {
        val state = state(23)
        state.updateEndContentHeight(73)
        val geometry = state.layout!!
        assertEquals(400f, geometry.maxScrollTop, 0f)
        state.beginScroll()
        state.scrollBy(Float.MAX_VALUE)
        state.finishScroll()
        assertEquals(333f, state.viewportTop, 0f)
        assertEquals(geometry.height.toFloat(), geometry.totalHeight + state.endContentHeight - state.viewportTop, 0f)
        assertEquals(state.pages.lastIndex, state.pageIndex)
        assertFalse(state.canScrollForward || state.canGoForward)
        assertNull(state.targetRow(1))
        state.beginScroll()
        state.scrollBy(-1f)
        // 最近的整行起点在 340px，吸附也不能越过实际 333px 的章末。
        state.settleScroll(state.scrollTarget)
        state.finishScroll()
        assertEquals(333f, state.viewportTop, 0f)
    }

    @Test fun aPartialLeadingLineAtTheEndStillCountsAsTheLastPage() {
        val state = RowPagingState(null)
        state.install(paginateLines(lines(3, height = 120), 200, 0), content, 200, 0)
        state.updateEndContentHeight(40)
        state.beginScroll()
        state.scrollBy(Float.MAX_VALUE)
        state.finishScroll()
        assertEquals(200f, state.viewportTop, 0f)
        assertEquals(1, state.row)
        assertEquals(2, state.pageIndex)
        assertFalse(state.canGoForward)
        assertNull(state.targetRow(1))
        state.move(1)
        assertEquals(200f, state.viewportTop, 0f)
    }

    @Test fun shrinkingTheFooterAfterVerticalScrollingRetractsItsBlankSpace() {
        val state = state(23)
        state.updateEndContentHeight(240)
        state.beginScroll()
        state.scrollBy(Float.MAX_VALUE)
        state.finishScroll()
        assertEquals(500f, state.viewportTop, 0f)
        state.updateEndContentHeight(60)
        assertEquals(320f, state.viewportTop, 0f)
        assertEquals(160, state.anchor.textOffset)
        assertEquals(state.pages.lastIndex, state.pageIndex)
        assertFalse(state.canScrollForward)
        // 横向定位仍保留原来末页的完整行；纵滑落定后才按内容收底。
        state.seekPage(state.pages.lastIndex)
        assertEquals(400f, state.viewportTop, 0f)
        state.beginScroll()
        state.finishScroll()
        assertEquals(320f, state.viewportTop, 0f)
    }

    @Test fun settledSliceLeavesThePartlyVisibleRowToTheNextScreen() {
        // 视口 205、行高 20：前 10 行占满 200，第 11 行只露出 5 像素。
        val layout = RowPaginationLayout(lines(count = 25, height = 20), 205, 0)
        val next = layout.screenEnd(0)
        assertEquals(10, next)
        // 拖动中保留露头的行，手指拖到哪画到哪。
        val dragging = layout.slice(0f, true)
        assertEquals(next + 1, dragging.lines.size)
        // 关闭局部行显示后，静止切片不再包含底部露头的行。
        val settled = layout.slice(0f, false)
        assertEquals(next, settled.lines.size)
        assertFalse(settled.lines.contains(layout.lines[next]))
    }

    @Test fun settledSliceToleratesAFloatCursorThatIsNotOnARowBoundary() {
        val layout = RowPaginationLayout(lines(count = 25, height = 20), 200, 0)
        // 章末收底会把游标停在非行首；判定按实际像素算，不能多露出下一行。
        val slice = layout.slice(3f, false)
        assertEquals(10, slice.lines.size)
        assertTrue(slice.lines.last().let { it.start == layout.lines[9].start })
    }

    @Test fun settledSliceUsesActualBottomAtChapterEndWithoutDroppingACompleteLine() {
        val layout = RowPaginationLayout(lines(count = 25, height = 20), 205, 0)
        // 章末收底可能停在行内：顶部偏移 17 后，第 11 行已完整进入视口。
        val slice = layout.slice(17f, false)
        assertEquals(11, slice.lines.size)
        assertEquals(layout.lines[10], slice.lines.last())
        assertEquals(12, layout.slice(17f, true).lines.size)
        assertEquals(10, layout.screenEnd(0))
    }

    @Test fun partialLineChoiceKeepsPageStartsHistoryAndAnchorsUnchanged() {
        val state = RowPagingState(null).apply {
            install(paginateLines(lines(), 205, 0), content, 205, 0)
            beginScroll()
            scrollBy(60f)
            finishScroll()
        }
        val layout = state.layout!!
        val anchor = state.anchor
        val top = state.viewportTop
        val starts = layout.pageStarts.toList()
        assertEquals(layout.slice(top, false).lines.size + 1, layout.slice(top, true).lines.size)
        assertEquals(anchor, state.anchor)
        assertEquals(starts, layout.pageStarts)
        state.move(1)
        assertEquals(layout.lines[layout.screenEnd(3)], layout.slice(state.viewportTop, false).lines.first())
        state.move(-1)
        assertEquals(top, state.viewportTop, 0f)
        assertEquals(anchor, state.anchor)
    }
}
