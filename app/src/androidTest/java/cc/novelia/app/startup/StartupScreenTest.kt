package cc.novelia.app.startup

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StartupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentLoadingStepAndRetryAreVisible() {
        var progress by mutableStateOf(StartupProgress(StartupStep.KEYWORDS))
        var retries = 0
        compose.setContent { NoveliaTheme("light") { StartupScreen(progress) { retries++ } } }
        compose.onNodeWithTag("startup-current-step").assertTextEquals(StartupStep.KEYWORDS.description)
        compose.onNodeWithText("正在加载").assertIsDisplayed()
        saveTestScreenshot("problem-startup-loading.png")
        compose.runOnIdle { progress = progress.copy(failed = true) }
        compose.onNodeWithText("待重试").assertIsDisplayed()
        compose.onNodeWithText("重试加载").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
