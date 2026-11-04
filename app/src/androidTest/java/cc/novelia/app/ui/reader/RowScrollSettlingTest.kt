package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class RowScrollSettlingTest {
    @get:Rule val compose = createComposeRule()
    private val state = RowPagingState(null)
    private val text = (1..400).joinToString(" ") { "第${it}行，松手时仍应连续显示正文。" }
    private var viewportHeight by mutableFloatStateOf(337f)
    private var showPartialLines by mutableStateOf(true)
    private val page get() = compose.onNodeWithTag("row-page")

    @After fun restoreAnimationClockBeforeActivityDisposal() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun show() {
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(listOf(ReadingParagraph(0, listOf(TextPart(text, "local")))),
                    ReaderSettings(paginationMode = "row", horizontalPageTurn = true, fontSize = 22f,
                        lineHeight = 1.7f, indent = false, eInkMode = false, rowShowPartialLines = showPartialLines), state,
                    Modifier.requiredSize(300.dp, viewportHeight.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move,
                    background = Color.White, foreground = Color.Black)
            }
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.runOnIdle { state.seekPage(2) }
    }

    /** 真实拖动后停住手指再抬起，让尾帧停在半行附近；不应凭旧速度继续惯性。 */
    private fun releaseBetweenRows(downwards: Boolean): Pair<Float, Float> {
        page.performTouchInput {
            down(Offset(150f, 160f))
            moveBy(Offset(0f, if(downwards) 45f else -45f), delayMillis = 200)
        }
        val top = state.layout!!.top(state.row)
        val height = state.layout!!.lines[state.row].height
        val desired = top + height * if(downwards) .65f else .35f
        page.performTouchInput { moveBy(Offset(0f, state.viewportTop - desired), delayMillis = 200) }
        val from = state.viewportTop
        val target = state.scrollTarget
        assertTrue(abs(from - target) > 3f)
        page.performTouchInput { advanceEventTime(250); up() }
        return from to target
    }

    private fun verifySmoothRelease(downwards: Boolean) {
        show()
        compose.mainClock.autoAdvance = false
        val (from, target) = releaseBetweenRows(downwards)
        val positions = mutableListOf(from)
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            positions += state.viewportTop
        }
        assertTrue("吸附应包含多个中间位置", positions.distinct().size >= 6)
        val distance = abs(target - from)
        positions.zipWithNext().forEach { (a, b) ->
            assertTrue("单帧不能直接跳过半行: $positions", abs(b - a) <= distance * .5f)
            assertTrue("停住后不能重新抛出惯性: $positions", b in minOf(from, target) - .01f..maxOf(from, target) + .01f)
        }
        assertEquals(target, state.viewportTop, .01f)
        assertFalse(state.scrolling)
    }

    @Test fun downwardReleaseSettlesOverSeveralFrames() = verifySmoothRelease(true)
    @Test fun upwardReleaseSettlesOverSeveralFrames() = verifySmoothRelease(false)

    @Test fun pressingAgainPausesSettlingAtTheCurrentPixelUntilRelease() {
        show()
        compose.mainClock.autoAdvance = false
        releaseBetweenRows(true)
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val interrupted = state.viewportTop
        page.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()
        assertEquals(interrupted, state.viewportTop, .01f)
        page.performTouchInput { advanceEventTime(320); up() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertFalse(state.scrolling)
        assertEquals(state.scrollTarget, state.viewportTop, .01f)
    }

    @Test fun inertiaAndSettlingHaveNoLargeFinalFrameJump() {
        show()
        compose.mainClock.autoAdvance = false
        page.performTouchInput { swipe(Offset(150f, 280f), Offset(150f, 100f), 160) }
        val positions = mutableListOf(state.viewportTop)
        var frames = 0
        do {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            positions += state.viewportTop
            frames++
        } while(state.scrolling && frames < 400)
        assertFalse("惯性必须正常结束", state.scrolling)
        assertTrue(frames > 10)
        assertTrue("最后一帧不能跳到另一行: ${positions.takeLast(8)}", abs(positions.last() - positions[positions.lastIndex - 1]) < 1f)
        assertEquals(state.scrollTarget, state.viewportTop, .01f)
    }

    @Test fun pressingAgainStopsPlatformInertiaWithoutStartingASnap() {
        show()
        compose.mainClock.autoAdvance = false
        page.performTouchInput { swipe(Offset(150f, 290f), Offset(150f, 50f), 80) }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val interrupted = state.viewportTop
        assertTrue(state.scrolling)
        page.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(interrupted, state.viewportTop, .01f)
        page.performTouchInput { advanceEventTime(400); up() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertFalse(state.scrolling)
        assertEquals(state.scrollTarget, state.viewportTop, .01f)
    }

    private fun pixels(): IntArray {
        val bitmap = page.captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    @Test fun stoppingKeepsTheSameBottomPartialLineAndTheSamePixels() {
        show()
        val rowHeight = state.layout!!.lines[state.row].height
        val height = (rowHeight * 8.75f).toInt()
        compose.runOnIdle { viewportHeight = height.toFloat() }
        compose.waitUntil(10_000) { state.ready && state.layout!!.height == height }
        val end = state.layout!!.screenEnd(state.row)
        val partial = state.layout!!.lines[end]
        compose.onNodeWithText(text.substring(partial.start, partial.end), substring = true, useUnmergedTree = true).assertIsDisplayed()
        val resting = pixels()
        val bottomHasInk = resting.takeLast(300 * (rowHeight / 2)).any { it != android.graphics.Color.WHITE }
        assertTrue("底部可见的下一行不能被整行删掉而留下大块空白", bottomHasInk)
        compose.runOnIdle { state.beginScroll() }
        assertArrayEquals(resting, pixels())
        compose.runOnIdle { state.finishScroll() }
        assertArrayEquals(resting, pixels())
    }

    @Test fun partialLineSwitchUpdatesImmediatelyAndKeepsScrollAndHorizontalTurnAnchors() {
        show()
        val rowHeight = state.layout!!.lines[state.row].height
        val height = (rowHeight * 8.75f).toInt()
        compose.runOnIdle { viewportHeight = height.toFloat() }
        compose.waitUntil(10_000) { state.ready && state.layout!!.height == height }
        val geometry = state.layout!!
        val anchor = state.anchor
        val visible = pixels()
        compose.runOnIdle { showPartialLines = false }
        fun assertBottomPartialLineHidden() {
            val partial = geometry.lines[geometry.screenEnd(state.row)]
            compose.onNodeWithText(text.substring(partial.start, partial.end), substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        assertBottomPartialLineHidden()
        val hidden = pixels()
        assertTrue(hidden.takeLast(300 * (rowHeight / 2)).all { it == android.graphics.Color.WHITE })
        compose.runOnIdle {
            assertSame(geometry, state.layout)
            assertEquals(anchor, state.anchor)
            state.beginScroll()
        }
        assertArrayEquals(visible, pixels())
        compose.runOnIdle { state.finishScroll() }
        assertArrayEquals(hidden, pixels())
        page.performTouchInput { swipeLeft() }
        assertBottomPartialLineHidden()
        page.performTouchInput { swipeRight() }
        assertArrayEquals(hidden, pixels())
        compose.runOnIdle {
            assertEquals(anchor, state.anchor)
            showPartialLines = true
        }
        assertArrayEquals(visible, pixels())
    }

    @Test fun externalSearchCancelsAnInFlightSnapWithoutOverwritingItsAnchor() {
        show()
        compose.mainClock.autoAdvance = false
        releaseBetweenRows(false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { state.find(0, 17, 0) }
        val target = state.viewportTop
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(target, state.viewportTop, .01f)
        assertEquals(17, state.anchor.textOffset)
    }

    @Test fun releasingAnOldDragAfterSearchDoesNotReplaceTheExactSearchOffset() {
        show()
        compose.mainClock.autoAdvance = false
        page.performTouchInput { down(center); moveBy(Offset(0f, -55f), delayMillis = 200) }
        compose.runOnIdle { state.find(0, 17, 0) }
        val target = state.viewportTop
        compose.mainClock.advanceTimeByFrame()
        page.performTouchInput { advanceEventTime(250); up() }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(target, state.viewportTop, .01f)
        assertEquals(17, state.anchor.textOffset)
    }
}
