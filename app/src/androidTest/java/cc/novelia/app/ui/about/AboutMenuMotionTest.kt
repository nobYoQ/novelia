package cc.novelia.app.ui.about

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutMenuMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realAboutMenuEntersFromBelowAndStaysStaticInReducedAndEInkModes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        var mode by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NoveliaTheme("light") {
                AppInteractionMode(eInk = mode == 2, reducedMotion = mode == 1) {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val feedback = remember { SnackbarHostState() }
                    val controller = remember { AppController(app, nav, scope, feedback) }
                    key(mode) { AboutScreen(controller) }
                }
            }
        }
        fun menuTop() = compose.onNodeWithText("Novelia 使用教程").getUnclippedBoundsInRoot().top
        compose.mainClock.advanceTimeBy(32)
        val entering = menuTop()
        compose.mainClock.advanceTimeBy(320)
        val settled = menuTop()
        assertTrue("关于页实际菜单应向上浮现，而不只是播放顶端彩蛋", entering > settled)
        for(staticMode in 1..2) {
            compose.runOnIdle { mode = staticMode }
            compose.mainClock.advanceTimeBy(32)
            assertEquals("静态模式首帧即到位", settled, menuTop())
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Novelia 使用教程").assertIsDisplayed()
    }
}
