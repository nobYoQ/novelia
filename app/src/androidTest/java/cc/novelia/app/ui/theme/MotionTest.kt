package cc.novelia.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.MotionContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidTargetChangesKeepOneChildAndItsRememberedState() {
        var identity by mutableStateOf("first")
        var activeChildren = 0
        var launchedLoads = 0
        compose.setContent {
            MaterialTheme {
                MotionContent(identity) {
                    DisposableEffect(Unit) {
                        activeChildren++
                        onDispose { activeChildren-- }
                    }
                    LaunchedEffect(Unit) { launchedLoads++ }
                    var selected by remember { mutableIntStateOf(0) }
                    Column {
                        Text(identity)
                        Text("selected: $selected")
                        Button(onClick = { selected++ }) { Text("Select") }
                    }
                }
            }
        }
        compose.onNodeWithText("Select").performClick()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { identity = "second" }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { identity = "third" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("second").assertDoesNotExist()
        compose.onNodeWithText("third").assertExists()
        compose.onNodeWithText("selected: 1").assertExists()
        compose.runOnIdle {
            assertEquals("Transitions must not retain a second page", 1, activeChildren)
            assertEquals("Transitions must not restart child loads", 1, launchedLoads)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("selected: 1").assertIsDisplayed()
    }

    @Test fun enablingReducedMotionDuringTransitionImmediatelyShowsFullContent() {
        var identity by mutableIntStateOf(0)
        var reducedMotion by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                Box(Modifier.size(80.dp).background(Color.White).testTag("frame")) {
                    MotionContent(identity, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { identity++ }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { reducedMotion = true }
        compose.mainClock.advanceTimeByFrame()
        val image = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        // Check the top as well as the centre: an unfinished slide would expose white at the top.
        assertEquals(Color.Red, image[image.width / 2, 1])
        assertEquals(Color.Red, image[image.width / 2, image.height / 2])
        compose.runOnIdle { reducedMotion = false }
        compose.mainClock.advanceTimeByFrame()
        val restored = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals("Turning motion back on must not replay visible content", Color.Red, restored[restored.width / 2, 1])
        compose.mainClock.autoAdvance = true
    }

    @Test fun skippingInitialRevealStillAnimatesTheNextTargetChange() {
        var identity by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(80.dp).background(Color.White).testTag("frame")) {
                MotionContent(identity, Modifier.fillMaxSize(), animateInitial = false) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val initial = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals("The first frame must be fully visible", Color.Red, initial[initial.width / 2, 1])
        compose.runOnIdle { identity++ }
        compose.mainClock.advanceTimeBy(32)
        val changing = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals("A later target still enters from below", Color.White, changing[changing.width / 2, 1])
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val settled = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
        assertEquals(Color.Red, settled[settled.width / 2, 1])
    }
}
