package cc.novelia.app.ui.discover

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.CollapsibleCloudFilters
import cc.novelia.app.ui.components.rememberCloudFilterCollapse
import cc.novelia.app.ui.discover.SearchAssistantPanel
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudFilterDoesNotScrollResultsWhenToggledAtTheTop() = checkTopPosition(assistant = false)
    @Test fun auxiliarySearchDoesNotScrollResultsWhenToggledAtTheTop() = checkTopPosition(assistant = true)
    @Test fun cloudFilterKeepsScrolledBookAttachedToTheViewportThroughoutAnimation() = checkTopPosition(assistant = false, initialIndex = 40)
    @Test fun auxiliarySearchKeepsScrolledBookAttachedToTheViewportThroughoutAnimation() = checkTopPosition(assistant = true, initialIndex = 40)
    @Test fun reducedMotionFilterPreservesTheScrolledBook() = checkTopPosition(assistant = false, initialIndex = 40, reduced = true)
    @Test fun eInkAssistantPreservesTheScrolledBook() = checkTopPosition(assistant = true, initialIndex = 40, eInk = true)
    @Test fun cloudFilterDoesNotJumpWhenToggledFromTheEnd() = checkTopPosition(assistant = false, atEnd = true)
    @Test fun auxiliarySearchDoesNotJumpWhenToggledFromTheEnd() = checkTopPosition(assistant = true, atEnd = true)

    private fun checkTopPosition(assistant: Boolean, initialIndex: Int = 0, reduced: Boolean = false, eInk: Boolean = false, atEnd: Boolean = false) {
        var expanded by mutableStateOf(false)
        val initialOffset = if(initialIndex == 0) 0 else 13
        val list = LazyListState(if(atEnd) 99 else initialIndex, initialOffset)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced, LocalEInkMode provides eInk) {
                Column(Modifier.fillMaxSize()) {
                    if(assistant) SearchAssistantPanel("", emptyList(), expanded, { expanded = it }, {}, { _, _ -> }, {})
                    else CollapsibleCloudFilters(expanded, { expanded = !expanded }, "全部收藏", 220.dp) {
                        Text("筛选条件", Modifier.height(220.dp))
                    }
                    AppLazyColumn(Modifier.weight(1f), state = list, listModifier = Modifier.testTag("result-viewport")) {
                        items(100, key = { it }) { index ->
                            // Discovery rows also animate placement; exercise this alongside viewport resizing.
                            val motion = if(assistant && !reduced && !eInk) Modifier.animateItem(
                                fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)) else Modifier
                            Text("书目 $index", motion.fillMaxWidth().height((96 + index % 3 * 16).dp).testTag("result-$index"))
                        }
                    }
                    }
                }
            }
        }
        compose.waitForIdle()
        // Initial scroll-to-end is clamped to fill the viewport. Keep that first visible book;
        // shrinking the viewport is allowed to move the last book below its lower edge.
        val anchorIndex = list.firstVisibleItemIndex
        val anchorOffset = list.firstVisibleItemScrollOffset
        fun relativePosition(): Float {
            val viewport = compose.onNodeWithTag("result-viewport").getUnclippedBoundsInRoot()
            val book = compose.onNodeWithTag("result-$anchorIndex").getUnclippedBoundsInRoot()
            return (book.top - viewport.top).value
        }
        val before = relativePosition()
        compose.mainClock.autoAdvance = false
        // Include reversing a transition before it finishes, not just its settled endpoints.
        for((target, frames) in listOf(true to 24, false to 24, true to 5, false to 4, true to 24, false to 24)) {
            compose.runOnIdle { expanded = target }
            repeat(frames) { frame ->
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle {
                    assertEquals("Panel toggle must not skip books (expanded=$target, frame=$frame)", anchorIndex, list.firstVisibleItemIndex)
                    assertEquals("Panel toggle must not scroll a partially hidden first book", anchorOffset, list.firstVisibleItemScrollOffset)
                }
                assertEquals("Book must move with its viewport on every frame (expanded=$target, frame=$frame)", before, relativePosition(), 1f)
            }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun automaticCollapseDoesNotCancelTheGestureOrTriggerAnotherCollapse() {
        var expanded by mutableStateOf(true)
        var collapses = 0
        val list = LazyListState(40, 13)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    CollapsibleCloudFilters(expanded, { expanded = !expanded }, "全部收藏", 160.dp) {
                        Text("完整条件", Modifier.height(160.dp))
                    }
                    val collapse = rememberCloudFilterCollapse(true, expanded) { collapses++; expanded = false }
                    AppLazyColumn(Modifier.weight(1f).testTag("scrolling-results")
                        .nestedScroll(collapse), state = list) {
                        items(200, key = { it }) { Text("书目 $it", Modifier.height(64.dp)) }
                    }
                }
            }
        }
        compose.onNodeWithTag("scrolling-results").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(expanded)
            assertEquals(1, collapses)
            assertTrue(list.firstVisibleItemIndex > 40)
        }
        compose.onNodeWithTag("scrolling-results").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, collapses) }
    }

    @Test fun auxiliarySearchRestoresItsEditingPositionAfterStaticCollapse() {
        var expanded by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEInkMode provides true, LocalReducedMotion provides true) {
                    SearchAssistantPanel("", emptyList(), expanded, { expanded = it }, {}, { _, _ -> }, {})
                }
            }
        }
        compose.onNodeWithTag("assistant-all").performScrollTo().assertIsDisplayed()
        val before = compose.onNodeWithTag("assistant-all").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("search-assistant-toggle").performClick()
        compose.onNodeWithTag("assistant-all").assertDoesNotExist()
        compose.onNodeWithTag("search-assistant-toggle").performClick()
        compose.onNodeWithTag("assistant-all").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("assistant-all").getUnclippedBoundsInRoot().top)
    }
}
