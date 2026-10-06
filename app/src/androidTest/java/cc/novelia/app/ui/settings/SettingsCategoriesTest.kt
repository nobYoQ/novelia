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
        compose.onNodeWithText("网络与同步").performScrollTo().performClick()
        compose.onNodeWithText("书源线路").assertIsDisplayed()
        compose.onNodeWithText("网络诊断与日志").assertIsDisplayed()
        compose.onNodeWithText("仅在 Wi-Fi 下载").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("书源线路").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("书架与下载").performScrollTo().performClick()
        compose.onNodeWithText("移出书架时删除本地副本").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("书源线路").assertDoesNotExist()
    }
}
