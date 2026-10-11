package cc.novelia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import cc.novelia.app.ui.components.base.AsyncContent

class AsyncContentRevealTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedSuccessRevealsAfterLoadingAndCanBeMadeStaticMidAnimation() {
        val result = CompletableDeferred<String>()
        var reduced by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                Box(Modifier.size(80.dp).background(Color.White).testTag("reveal-frame")) {
                    AsyncContent("book", load = { result.await() }, revealContent = true) { _, _ ->
                        Box(Modifier.fillMaxSize().background(Color.Red).testTag("loaded-content"))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { result.complete("loaded") }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("loaded-content").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(32)
        val entering = compose.onNodeWithTag("reveal-frame").captureToImage().toPixelMap()
        assertEquals("数据到达后才开始浮现", Color.White, entering[entering.width / 2, 1])
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        val settled = compose.onNodeWithTag("reveal-frame").captureToImage().toPixelMap()
        assertEquals(Color.Red, settled[settled.width / 2, 1])
        compose.mainClock.autoAdvance = true
    }
}
