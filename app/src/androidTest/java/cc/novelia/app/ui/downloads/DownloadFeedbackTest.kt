package cc.novelia.app.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerSnackbarHost
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: AppController

    @Test fun startFeedbackStaysOnTheCurrentPageUntilItsActionIsClicked() {
        setup()
        compose.runOnIdle {
            controller.message("已开始下载", actionLabel = "查看下载") { controller.go("downloads", replaceTop = true) }
        }
        compose.onNodeWithText("已开始下载").assertIsDisplayed()
        compose.runOnIdle { assertEquals("book", controller.nav.currentDestination?.route) }
        compose.onNodeWithText("查看下载").performClick()
        compose.runOnIdle { assertEquals("downloads", controller.nav.currentDestination?.route) }
    }

    @Test fun completionActionOpensDownloadsWithoutDuplicatingTheCurrentDestination() {
        setup()
        compose.runOnIdle {
            controller.go("downloads")
            controller.celebrate("第1卷下载完成", MidoriSticker.Celebrate, actionLabel = "查看下载") {
                controller.go("downloads", replaceTop = true)
            }
        }
        compose.onNodeWithText("第1卷下载完成").assertIsDisplayed()
        compose.onNodeWithText("查看下载").performClick()
        compose.runOnIdle { controller.back() }
        compose.runOnIdle { assertEquals("book", controller.nav.currentDestination?.route) }
    }

    private fun setup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            AppInteractionMode(false, true) {
                NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val snackbar = remember { SnackbarHostState() }
                    controller = remember { AppController(app, nav, scope, snackbar) }
                    Scaffold(snackbarHost = { StickerSnackbarHost(snackbar) }) { padding ->
                        Box(Modifier.padding(padding)) {
                            NavHost(nav, startDestination = "book") {
                                composable("book") { Text("文库详情") }
                                composable("downloads") { Text("下载管理") }
                            }
                        }
                    }
                }
            }
        }
    }
}
