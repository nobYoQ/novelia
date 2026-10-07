package cc.novelia.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.BuildConfig
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.about.AboutScreen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutWaveMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fiveVersionTapsActuallyAnimateTheStickerPixels() = verifyWave(false, false)
    @Test fun reducedMotionKeepsTheTriggeredStickerStatic() = verifyWave(true, false)
    @Test fun eInkKeepsTheTriggeredStickerStatic() = verifyWave(false, true)

    private fun verifyWave(reduced: Boolean, eInk: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            NoveliaTheme("light") {
                AppInteractionMode(eInk, reduced) {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val feedback = remember { SnackbarHostState() }
                    val controller = remember { AppController(app, nav, scope, feedback) }
                    Box(Modifier.size(320.dp, 400.dp).background(Color.White).testTag("wave-frame")) {
                        NavHost(nav, startDestination = "home") {
                            composable("home") { Button(onClick = { nav.navigate("about") }) { Text("打开关于") } }
                            composable("about") { AboutScreen(controller) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("打开关于").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(5) {
            compose.onNodeWithText("Android ${BuildConfig.VERSION_NAME} · 非官方客户端").performClick()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithContentDescription("小绿向你招手").assertIsDisplayed()
        val first = compose.onNodeWithTag("wave-frame").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(240)
        val second = compose.onNodeWithTag("wave-frame").captureToImage().toPixelMap()
        var changed = 0
        // 只比较贴纸所在的上半部，排除下方版本号点击涟漪造成的像素变化。
        for(y in 0 until first.height / 2) for(x in 0 until first.width) {
            if(first[x, y] != second[x, y]) changed++
        }
        if(reduced || eInk) assertEquals("静态模式只展示举手贴纸", 0, changed)
        else assertTrue("举手贴纸在触发后必须有实际的动画帧变化，变化像素：$changed", changed > 100)
        compose.mainClock.autoAdvance = true
    }
}
