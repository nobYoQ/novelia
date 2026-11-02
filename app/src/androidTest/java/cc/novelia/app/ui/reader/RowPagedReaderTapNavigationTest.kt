package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RowPagedReaderTapNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val state = RowPagingState(null)
    private var settings by mutableStateOf(ReaderSettings(paginationMode = "row", tapPageTurn = true,
        horizontalPageTurn = true, eInkMode = true, indent = false, fontSize = 20f))
    private val turns = mutableListOf<Int>()
    private var menus = 0
    private var navigations = 0
    private var selections = 0
    private val content = listOf(ReadingParagraph(0, listOf(TextPart("行带分页与点击区域共同启用的测试正文。".repeat(300), "local"))))

    private fun show(animated: Boolean = false) {
        settings = settings.copy(eInkMode = !animated)
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RowPagedReader(content, settings, state, Modifier.requiredSize(327.dp, 420.dp).testTag("row-page"),
                    imageModel = { null }, onToggleMenu = { menus++ }, onSelect = { selections++ },
                    onPage = { turns += it; state.move(it) }, onNavigate = { navigations++ },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp))
            }
        } }
        compose.waitUntil(10_000) { state.ready }
    }

    private fun verticalAndHorizontalGesturesDoNotAlsoClick(animated: Boolean) {
        show(animated)
        val page = compose.onNodeWithTag("row-page")
        // 从单击“下一页”区域的留白处纵滑，只移动行位置，不应调用翻页或菜单。
        page.performTouchInput { swipe(Offset(width - 4f, 250f), Offset(width - 4f, 170f), 1000) }
        var anchor = 0
        var next = 0
        compose.runOnIdle {
            anchor = state.row
            next = state.targetRow(1)!!
            assertTrue(anchor > 0)
            assertTrue(turns.isEmpty())
            assertEquals(0, menus)
            assertEquals(0, navigations)
        }
        // 点击应从当前行翻整屏，回退仍回到纵滑之后的原行。
        page.performTouchInput { click(Offset(width - 4f, centerY)) }
        compose.runOnIdle { assertEquals(next, state.row); assertEquals(listOf(1), turns) }
        page.performTouchInput { click(Offset(4f, centerY)) }
        compose.runOnIdle { assertEquals(anchor, state.row); assertEquals(listOf(1, -1), turns) }
        page.performTouchInput { swipe(Offset(280f, 210f), Offset(50f, 210f), 300) }
        compose.runOnIdle { assertEquals(next, state.row); assertEquals(listOf(1, -1, 1), turns) }
        page.performTouchInput { swipe(Offset(50f, 210f), Offset(280f, 210f), 300) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(anchor, state.row)
            assertEquals(listOf(1, -1, 1, -1), turns)
            assertEquals(0, menus)
            assertEquals(0, navigations)
        }
        page.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(4, turns.size) }
    }

    @Test fun tapsAndDragsRemainIndependentWithoutAnimation() = verticalAndHorizontalGesturesDoNotAlsoClick(false)
    @Test fun tapsAndDragsRemainIndependentWithAnimation() = verticalAndHorizontalGesturesDoNotAlsoClick(true)

    @Test fun gesturesInWidthLimitedOuterMarginsShareTheTapViewport() {
        settings = settings.copy(width = 240f)
        show()
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { swipe(Offset(width - 4f, 250f), Offset(width - 4f, 170f), 1000) }
        var next = 0
        compose.runOnIdle { assertTrue(state.row > 0); assertTrue(turns.isEmpty()); next = state.targetRow(1)!! }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(next, state.row); assertEquals(listOf(1), turns) }
        page.performTouchInput { click(Offset(width - 4f, centerY)) }
        compose.runOnIdle { assertEquals(listOf(1, 1), turns); assertEquals(0, menus); assertEquals(0, navigations) }
    }

    @Test fun doubleTapAndPendingSingleTapDoNotLeakIntoScrolling() {
        show()
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { doubleClick(Offset(280f, 60f), delayMillis = 100) }
        compose.waitUntil(3_000) { navigations == 1 }
        compose.runOnIdle { assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        compose.mainClock.advanceTimeBy(400)
        // 同一区域的第二次触摸变成拖动时，应取消尚未确认的单击。
        page.performTouchInput {
            advanceEventTime(400)
            click(Offset(280f, 60f))
            advanceEventTime(100)
            swipe(Offset(280f, 60f), Offset(280f, 160f), 250)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertTrue(turns.isEmpty()); assertEquals(1, navigations); assertEquals(0, menus) }
        page.performTouchInput { advanceEventTime(400); click(Offset(280f, 60f)) }
        compose.waitUntil(3_000) { turns == listOf(1) }
    }

    @Test fun cancelledReversedAndMultiplePointerGesturesNeverBecomeZoneTaps() {
        show()
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { down(Offset(280f, centerY)); moveBy(Offset(-100f, 0f)); cancel() }
        page.performTouchInput {
            down(Offset(280f, centerY))
            moveBy(Offset(-100f, 0f), delayMillis = 200)
            moveTo(Offset(280f, centerY), delayMillis = 200)
            advanceEventTime(200)
            up()
        }
        page.performTouchInput {
            down(Offset(280f, centerY))
            down(pointerId = 1, position = center)
            moveBy(Offset(-100f, 0f))
            up()
            up(pointerId = 1)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(0, state.row)
            assertTrue(turns.isEmpty())
            assertEquals(0, menus)
            assertEquals(0, navigations)
        }
    }

    @Test fun longPressAndDisabledHorizontalSwipesDoNotTriggerZoneActions() {
        show()
        val page = compose.onNodeWithTag("row-page")
        page.performTouchInput { longClick(Offset(280f, 60f)) }
        compose.runOnIdle { assertEquals(1, selections); settings = settings.copy(horizontalPageTurn = false) }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, state.row); assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        page.performTouchInput { swipe(Offset(280f, 250f), Offset(280f, 170f), 1000) }
        compose.runOnIdle { assertTrue(state.row > 0); assertTrue(turns.isEmpty()) }
        page.performTouchInput { click(Offset(280f, centerY)) }
        compose.runOnIdle { assertEquals(listOf(1), turns); assertEquals(0, menus); assertEquals(0, navigations) }
    }

    @Test fun customZoneActionsAndDisabledTapPagingDoNotDisableGestures() {
        show()
        val page = compose.onNodeWithTag("row-page")
        compose.runOnIdle {
            settings = settings.copy(tapZoneMap = ReaderTapZoneMap.encode(ReaderTapZoneMap.Default
                .withAction(2, 1, false, ReaderTapAction.None)
                .withAction(1, 1, false, ReaderTapAction.NextPage)))
        }
        page.performTouchInput { click(Offset(280f, centerY)) }
        compose.runOnIdle { assertTrue(turns.isEmpty()); assertEquals(0, menus) }
        page.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(1), turns) }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(listOf(1, 1), turns); settings = settings.copy(tapPageTurn = false) }
        page.performTouchInput { click(Offset(280f, centerY)) }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(2, turns.size) }
        page.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(listOf(1, 1, 1), turns); assertEquals(1, menus) }
    }
}
