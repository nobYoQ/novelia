package cc.novelia.app.ui.about

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.updates.APP_RELEASES_URL
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.ui.feedback.AppUpdateDialog
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.downloadAppRelease
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class AppReleaseDownloadUiTest {
    @get:Rule val compose = createComposeRule()

    private fun app(): NoveliaApplication {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        return app
    }

    @Test fun missingApkShowsAnErrorInTheDialogAndKeepsRetryAvailable() {
        val app = app()
        val release = AppRelease("v99.0.0", "$APP_RELEASES_URL/tag/v99.0.0", body = "本次更新说明")
        var queued = false
        compose.setContent {
            NoveliaTheme("light") {
                AppInteractionMode(eInk = false, reducedMotion = true) {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val feedback = remember { SnackbarHostState() }
                    val controller = remember { AppController(app, nav, scope, feedback) }
                    var error by remember { mutableStateOf<String?>(null) }
                    AppUpdateDialog(controller, release, onLater = {}, onIgnore = {}, onUpdate = {
                        controller.downloadAppRelease(release, onQueued = { queued = true }, onError = { error = it })
                    }, downloadError = error)
                }
            }
        }
        compose.onNodeWithText("下载更新").performClick()
        compose.onNodeWithTag("app-update-error").assertIsDisplayed().assertTextEquals("此发行版暂未提供适合当前设备的 APK")
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.onNodeWithText("下载更新").assertIsEnabled().performClick()
        compose.onNodeWithTag("app-update-error").assertIsDisplayed()
        compose.runOnIdle { assertFalse(queued); assertFalse(app.appReleaseDownloads.preparing.value) }
    }

    @Test fun aboutProvidesStablePreviewAndReleaseNotesInEInkMode() {
        val app = app()
        compose.setContent {
            NoveliaTheme("light") {
                AppInteractionMode(eInk = true, reducedMotion = true) {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val feedback = remember { SnackbarHostState() }
                    val controller = remember { AppController(app, nav, scope, feedback) }
                    AboutScreen(controller)
                }
            }
        }
        for(title in listOf("下载新版本", "下载预览包", "发行说明")) {
            // 电子纸禁用连续滚动，使用与用户相同的翻屏按钮定位菜单。
            for(page in 0..5) {
                if(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() && compose.onNodeWithText(title).isDisplayed()) break
                compose.onNodeWithText("下一屏").assertIsEnabled().performClick()
            }
            compose.onNodeWithText(title).assertIsDisplayed().assertHasClickAction()
        }
    }
}
