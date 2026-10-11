@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSheet

@RunWith(AndroidJUnit4::class)
class SheetGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tallScrollPanelKeepsItsViewportStableWhileOpeningAndDraggingUp() {
        var visible by mutableStateOf(false)
        lateinit var sheetState: SheetState
        val viewportHeights = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                if (visible) AppSheet(onDismissRequest = { visible = false }, sheetState = sheetState) {
                    AppScrollColumn(Modifier.fillMaxWidth().testTag("scroll-panel")
                        .onSizeChanged { viewportHeights += it.height }) {
                        repeat(30) { Text("菜单 $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                }
            }
        }
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(sheetState.isAnimationRunning)
            assertTrue(viewportHeights.isNotEmpty())
            assertEquals("展开过程中不应因顶部 inset 改变而反复重测内容高度", 1, viewportHeights.distinct().size)
        }
        var expandedOffset = 0f
        compose.runOnIdle { expandedOffset = sheetState.requireOffset() }
        repeat(3) {
            compose.onNodeWithTag("scroll-panel").performTouchInput {
                swipe(center, center - Offset(0f, 60f), durationMillis = 500)
            }
            compose.runOnIdle {
                assertTrue(visible)
                assertFalse(sheetState.isAnimationRunning)
                assertEquals(expandedOffset, sheetState.requireOffset(), 1f)
                assertEquals(1, viewportHeights.distinct().size)
            }
        }
    }

    @Test fun upwardScrollAtListEndKeepsSheetOpenAndDownwardDragDismissesIt() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) AppSheet(onDismissRequest = { visible = false }) {
                    Text("测试面板", Modifier.fillMaxWidth().height(80.dp).testTag("sheet-header"))
                    AppLazyColumn(Modifier.fillMaxWidth().height(400.dp), listModifier = Modifier.testTag("sheet-list")) {
                        items(30) { index -> Text("项目 $index", Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }

        compose.onNodeWithTag("sheet-list").performScrollToIndex(29)
        compose.onNodeWithTag("sheet-list").performTouchInput { swipeUp() }
        compose.onNodeWithText("测试面板").assertIsDisplayed()
        compose.runOnIdle { assertTrue(visible) }

        compose.onNodeWithTag("sheet-header").performTouchInput {
            swipe(center, center + Offset(0f, 600f), durationMillis = 500)
        }
        compose.waitUntil(5_000) { !visible }
        compose.runOnIdle { assertFalse(visible) }
    }
}
