package cc.novelia.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.settings.*
import cc.novelia.app.ui.shelf.HistoryScreen
import cc.novelia.app.ui.tools.ToolsScreen
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ProfileDetailLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var paging = false

    @Test fun largeTextAndEInkCanScrollPastControlsToAllActions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        var page by mutableStateOf("network")
        var eInk by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppInteractionMode(eInk = eInk, reducedMotion = true) {
                    NoveliaTheme(if(eInk) "light" else "dark") {
                        val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        Box(Modifier.width(320.dp).fillMaxHeight()) { key(page, eInk) {
                            when(page) {
                                "network" -> NetworkSyncScreen(controller)
                                "history" -> HistoryScreen(controller)
                                "backup" -> LibraryBackupScreen(controller)
                                "tools" -> ToolsScreen(controller)
                                else -> SettingsScreen(controller, "READING")
                            }
                        } }
                    }
                }
            }
        }
        for(mode in listOf(false, true)) {
            paging = mode
            compose.runOnIdle { eInk = mode; page = "network" }
            listOption("网络诊断与日志").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            listOption("启动时检查应用更新").assertIsDisplayed()
            saveTestScreenshot("profile-secondary-network-${if(mode) "eink" else "large-dark"}.png")
            compose.runOnIdle { page = "history" }
            listOption("原站云端").performClick()
            listOption("登录").assertIsDisplayed()
            compose.runOnIdle { page = "backup" }
            listOption("选择备份并检查").assertIsDisplayed().assertIsEnabled()
            saveTestScreenshot("profile-secondary-backup-${if(mode) "eink" else "large-dark"}.png")
            compose.runOnIdle { page = "tools" }
            compose.onNodeWithText("片假名统计").performScrollTo().performClick()
            compose.onNodeWithText("粘贴或编辑文本").performScrollTo().performTextInput("テスト")
            compose.onNodeWithText("开始处理").performScrollTo().assertIsEnabled()
            compose.runOnIdle { page = "reading" }
            listOption("朗读通知").assertIsDisplayed()
        }
    }

    private fun listOption(title: String): SemanticsNodeInteraction {
        if(paging) {
            // 电子纸关闭惯性滚动，不提供 ScrollToIndex；验证用户实际的翻屏入口。
            repeat(20) {
                if(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() && compose.onNodeWithText(title).isDisplayed()) {
                    return compose.onNodeWithText(title)
                }
                compose.onNodeWithText("下一屏").assertIsEnabled().performClick()
                compose.waitForIdle()
            }
            error("翻屏后仍未找到 $title")
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }
}
