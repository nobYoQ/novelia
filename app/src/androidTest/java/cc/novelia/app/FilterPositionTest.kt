package cc.novelia.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.CollapsibleCloudFilters
import cc.novelia.app.ui.LocalEInkMode
import cc.novelia.app.ui.LocalReducedMotion
import cc.novelia.app.ui.SearchAssistantPanel
import cc.novelia.app.ui.preserveFilterResultPosition
import cc.novelia.app.ui.rememberCloudFilterCollapse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudFilterKeepsLongListBookAtTheSameScreenPosition() = checkResultAnchor(assistant = false, reduced = false)
    @Test fun auxiliarySearchKeepsLongListBookAtTheSameScreenPosition() = checkResultAnchor(assistant = true, reduced = false)
    @Test fun reducedMotionFilterKeepsLongListBookAtTheSameScreenPosition() = checkResultAnchor(assistant = false, reduced = true)

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
                    LazyColumn(Modifier.weight(1f).testTag("scrolling-results")
                        .nestedScroll(collapse).preserveFilterResultPosition(list), state = list) {
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

    private fun checkResultAnchor(assistant: Boolean, reduced: Boolean) {
        var expanded by mutableStateOf(false)
        val list = LazyListState(40, 13)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced, LocalDensity provides Density(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        if(assistant) SearchAssistantPanel("", emptyList(), expanded, { expanded = it }, {}, { _, _ -> }, {})
                        else CollapsibleCloudFilters(expanded, { expanded = !expanded }, "全部收藏", 220.dp) {
                            Text("筛选条件", Modifier.height(220.dp))
                        }
                        LazyColumn(Modifier.weight(1f).preserveFilterResultPosition(list), state = list) {
                            items(200, key = { it }) { index ->
                                Text("书目 $index", Modifier.fillMaxWidth().height(48.dp).testTag("result-$index"))
                            }
                        }
                    }
                }
            }
        }
        fun anchor() = compose.onNodeWithTag("result-50").getUnclippedBoundsInRoot().top.value
        val before = anchor()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(96)
        assertEquals("Expansion must not move the visible book", before, anchor(), 2f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(before, anchor(), 2f)
        compose.runOnIdle { expanded = false }
        compose.waitForIdle()
        assertEquals("Collapse must return the same long-list viewport", before, anchor(), 2f)
        compose.runOnIdle {
            assertEquals(40, list.firstVisibleItemIndex)
            assertEquals(13, list.firstVisibleItemScrollOffset)
        }
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
