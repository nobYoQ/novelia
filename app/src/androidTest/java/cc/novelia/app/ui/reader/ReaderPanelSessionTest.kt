@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPanelSessionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentBookSettingsExposeScopeDifferencesAndRestoreDefaultFollowing() {
        var independent by mutableStateOf(true)
        val defaults = ReaderSettings(fontSize = 16.629105f)
        var book by mutableStateOf(defaults.copy(fontSize = 24f, lineHeight = 2.1f, theme = "dark"))
        compose.setContent {
            MaterialTheme {
                ReaderPreferences(if(independent) book else defaults, independent, { independent = it }, defaultSettings = defaults) { book = it }
            }
        }
        compose.onNodeWithText("仅修改本书").assertIsDisplayed()
        compose.onNodeWithTag("reader-default-字号").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(defaults.fontSize, book.fontSize, 0f); assertEquals(2.1f, book.lineHeight, 0f); assertTrue(independent) }
        compose.onNodeWithText("跟随应用").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(defaults.resolvedTheme, book.resolvedTheme); assertEquals(2.1f, book.lineHeight, 0f) }
        compose.onNodeWithText("仅应用于这本书").performScrollTo().performClick()
        compose.onNodeWithText("正在修改默认设置").assertIsDisplayed()
        compose.runOnIdle { assertFalse(independent) }
    }

    @Test fun reopeningPreferencesResetsNavigationButKeepsSavedSettings() {
        var open by mutableStateOf(true)
        var settings by mutableStateOf(ReaderSettings(fontSize = 24f))
        lateinit var state: ReaderPreferencesState
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    state = rememberReaderPreferencesState(open)
                    if(open) AppSheet({ open = false }) { ReaderPreferences(settings, state = state) { settings = it } }
                }
            }
        }
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithTag("reader-toolbar-transparency").performScrollTo()
        compose.runOnIdle { assertTrue(state.scrollStates[1].value > 0) }
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("屏幕与朗读").performScrollTo().performClick()
        compose.onNodeWithText("关闭面板").performClick()
        compose.runOnIdle { open = true }
        compose.runOnIdle {
            assertEquals(0, state.tab.intValue)
            assertEquals("", state.expandedGroup.value)
            assertTrue(state.scrollStates.all { it.value == 0 })
            assertEquals(24f, settings.fontSize, .01f)
        }
    }

    @Test fun panelExpansionAnimatesAndReducedMotionTakesEffectImmediately() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        var reduced by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    ReaderPreferencesSheet({}) { expanded, setExpanded ->
                        Button(onClick = { setExpanded(!expanded) }) { Text("切换高度") }
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("reader-preferences-panel").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        val compact = height()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("切换高度").performClick()
            compose.mainClock.advanceTimeBy(80)
            val between = height()
            compose.mainClock.advanceTimeBy(1_500)
            val expanded = height()
            assertTrue("展开应经过中间高度", between > compact && between < expanded)
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("切换高度").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(compact, height(), 1f)
            compose.onNodeWithText("切换高度").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(expanded, height(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun preferencesHandleDragExpandsAndShortDownwardDragReturnsToHalfHeight() = verifyDragResizing(reduced = false)

    @Test fun reducedMotionPreferencesTitleDragKeepsTheSameTwoHeightStops() = verifyDragResizing(reduced = true)

    private fun verifyDragResizing(reduced: Boolean) {
        if(!reduced) assumeTrue(ValueAnimator.areAnimatorsEnabled())
        var open by mutableStateOf(true)
        var settings by mutableStateOf(ReaderSettings())
        lateinit var state: ReaderPreferencesState
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    state = rememberReaderPreferencesState(open)
                    if(open) ReaderPreferencesSheet({ open = false }) { expanded, setExpanded ->
                        ReaderPreferences(settings, state = state, modifier = Modifier.fillMaxSize(), headerActions = {
                            Button(onClick = { setExpanded(!expanded) }) { Text(if(expanded) "收起面板" else "展开面板") }
                        }) { settings = it }
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("reader-preferences-panel").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        fun drag(distanceDp: Float) {
            val distance = with(compose.density) { distanceDp.dp.toPx() }
            val target = if(reduced) compose.onNodeWithText("阅读偏好") else compose.onNodeWithTag("reader-preferences-drag-handle")
            target.performTouchInput { swipe(center, center + Offset(0f, distance), durationMillis = 300) }
            compose.waitForIdle()
        }
        val halfHeight = height()
        drag(-64f)
        val largeHeight = height()
        assertTrue("上拖应展开为大半屏", largeHeight > halfHeight * 1.4f)
        compose.onNodeWithText("收起面板").assertIsDisplayed()
        drag(40f)
        assertEquals("轻下拖应回到半屏", halfHeight, height(), 1f)
        compose.runOnIdle { assertTrue("大半屏下拖不应关闭面板", open) }

        // 取消和轻微手抖不提交高度变化。
        val cancelDistance = with(compose.density) { 64.dp.toPx() }
        compose.onNodeWithText("阅读偏好").performTouchInput {
            down(center); moveBy(Offset(0f, -cancelDistance)); cancel()
        }
        assertEquals(halfHeight, height(), 1f)
        drag(-8f)
        assertEquals(halfHeight, height(), 1f)

        // 列表与滑条仍优先消费自己的手势。
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithTag("reader-toolbar-transparency").performScrollTo()
        compose.runOnIdle { assertTrue(state.scrollStates[1].value > 0) }
        assertEquals(halfHeight, height(), 1f)
        compose.onNodeWithTag("reader-toolbar-transparency").performTouchInput {
            swipe(Offset(width * .25f, centerY), Offset(width * .8f, centerY), durationMillis = 300)
        }
        compose.runOnIdle { assertTrue(settings.resolvedToolbarTransparency > .25f) }
        assertEquals(halfHeight, height(), 1f)

        // 拖拽和原有展开按钮共用状态，切换高度不重建偏好会话。
        compose.onNodeWithText("展开面板").performClick()
        assertEquals(largeHeight, height(), 1f)
        drag(40f)
        compose.onNodeWithText("翻页").assertIsSelected()
        assertEquals(halfHeight, height(), 1f)
        compose.runOnIdle { assertTrue(settings.resolvedToolbarTransparency > .25f) }
        drag(100f)
        compose.onNodeWithTag("reader-preferences-panel").assertDoesNotExist()
        compose.runOnIdle { assertFalse(open) }
    }
}
