@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPanelSessionTest {
    @get:Rule val compose = createComposeRule()

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
}
