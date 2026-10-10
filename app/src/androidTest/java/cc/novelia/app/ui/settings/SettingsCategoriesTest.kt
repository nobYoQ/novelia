package cc.novelia.app.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SettingsCategoriesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoriesHideUnrelatedOptionsAndRestoreSelectedPage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NoveliaTheme("light") {
            SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }))
        } }
        compose.onNodeWithText("阅读与朗读").assertIsDisplayed()
        compose.onNodeWithText("默认阅读偏好").assertDoesNotExist()
        saveTestScreenshot("problem-settings-categories.png")
        option("网络与同步").performClick()
        option("书源线路").assertIsDisplayed()
        option("网络诊断与日志").assertIsDisplayed()
        compose.onNodeWithText("仅在 Wi-Fi 下载").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        option("书源线路").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        option("书架与下载").performClick()
        option("移出书架时删除本地副本").assertIsDisplayed()
        compose.onNodeWithText("书源线路").assertDoesNotExist()
    }

    @Test fun startupUpdatePreferenceCanBeDisabledAndRestored() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value.autoCheckAppUpdates
        try {
            app.store.update { it.copy(autoCheckAppUpdates = true) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NoveliaTheme("light") {
                SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), "NETWORK")
            } }
            option("启动时检查应用更新").assertIsOn().performClick()
            compose.onNodeWithText("启动时检查应用更新").assertIsOff()
            compose.runOnIdle { assertFalse(app.store.state.value.autoCheckAppUpdates) }
            runBlocking { app.store.flush() }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertFalse(app.store.state.value.autoCheckAppUpdates) }
            option("启动时检查应用更新").assertIsOff().performClick()
            compose.onNodeWithText("启动时检查应用更新").assertIsOn()
            compose.runOnIdle { assertTrue(app.store.state.value.autoCheckAppUpdates) }
        } finally {
            app.store.update { it.copy(autoCheckAppUpdates = original) }
            runBlocking { app.store.flush() }
        }
    }
    private fun option(title: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }
}
