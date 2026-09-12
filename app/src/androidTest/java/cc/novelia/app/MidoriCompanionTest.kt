package cc.novelia.app

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.LocalReducedMotion
import cc.novelia.app.ui.MidoriCompanion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MidoriCompanionTest {
    @get:Rule val compose = createComposeRule()
    private fun companion() = compose.onNodeWithContentDescription("小绿，阅读搭子")
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun rapidTapsChangeExpressionsWithoutMovingOrTriggeringLogin() {
        var logins = 0
        compose.setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.width(300.dp)) {
                        MidoriCompanion()
                        Button(onClick = { logins++ }) { Text("登录 / 注册") }
                    }
                }
            }
        }
        companion().assertIsEnabled().assert(state("等你打招呼"))
        val originalBounds = companion().fetchSemanticsNode().boundsInRoot
        val loginBounds = compose.onNodeWithText("登录 / 注册").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        repeat(6) { index ->
            companion().performClick()
            compose.mainClock.advanceTimeBy(80)
            companion().assert(state(listOf("开心地笑了", "向你眨眨眼", "收到你的喜欢啦")[index % 3]))
            assertEquals(originalBounds, companion().fetchSemanticsNode().boundsInRoot)
            assertEquals(loginBounds, compose.onNodeWithText("登录 / 注册").fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { assertEquals(0, logins) }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(4000) { compose.onAllNodes(state("等你打招呼")).fetchSemanticsNodes().size == 1 }
        companion().assertIsEnabled()
        compose.onNodeWithText("登录 / 注册").performClick()
        compose.runOnIdle { assertEquals(1, logins) }
    }

    @Test fun reducedMotionKeepsFeedbackAndHidingCancelsTheReaction() {
        var reduced by mutableStateOf(false)
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    MidoriCompanion(Modifier.width(300.dp), visible = visible)
                }
            }
        }
        companion().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        companion().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        companion().assertIsEnabled().assert(state("开心地笑了"))
        compose.onNodeWithText("好耶，收到喜欢！").assertDoesNotExist()
        companion().performClick()
        compose.mainClock.advanceTimeByFrame()
        companion().assert(state("向你眨眨眼"))
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeByFrame()
        companion().assertIsNotEnabled().assert(state("等你打招呼"))
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeByFrame()
        companion().assertIsEnabled().assert(state("等你打招呼"))
        compose.onNodeWithText("好耶，收到喜欢！").assertDoesNotExist()
        companion().performClick()
        compose.mainClock.advanceTimeByFrame()
        companion().assert(state("收到你的喜欢啦"))
        compose.mainClock.autoAdvance = true
    }

    @Test fun systemDisabledMotionKeepsTheReactionVisuallyStill() {
        // Run this case with Android's animator_duration_scale=0. Compose's test clock
        // otherwise uses its own duration scale, so verify the actual rendered pixels.
        assumeFalse(ValueAnimator.areAnimatorsEnabled())
        compose.setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    MidoriCompanion(Modifier.width(300.dp))
                }
            }
        }
        companion().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        companion().performClick()
        compose.mainClock.advanceTimeBy(650)
        val first = compose.onRoot().captureToImage()
        val firstPixels = IntArray(first.width * first.height).also { first.readPixels(it) }
        compose.mainClock.advanceTimeBy(220)
        val second = compose.onRoot().captureToImage()
        val secondPixels = IntArray(second.width * second.height).also { second.readPixels(it) }
        assertArrayEquals("System-disabled animation must leave the sticker and hearts stationary", firstPixels, secondPixels)
        companion().assert(state("开心地笑了"))
        compose.mainClock.autoAdvance = true
    }
}
