package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import cc.novelia.app.ui.components.base.PageControls

class PageControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun numberedPagesAndValidatedJumpUseZeroBasedCallbacks() {
        var page by mutableIntStateOf(0)
        compose.setContent { NoveliaTheme("light") {
            Box(Modifier.safeDrawingPadding().width(360.dp)) { PageControls(page, 35) { page = it } }
        } }
        compose.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        saveTestScreenshot("page-jump-collapsed.png")
        compose.onNodeWithContentDescription("第 35 页").performClick()
        compose.runOnIdle { assertEquals(34, page) }
        compose.onNodeWithContentDescription("下一页").assertIsNotEnabled()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").assertIsDisplayed()
        saveTestScreenshot("page-jump-expanded.png")
        compose.onNodeWithText("跳转页码").performTextInput("0")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("36")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("18")
        compose.onNodeWithText("跳转", substring = false).performClick()
        compose.runOnIdle { assertEquals(17, page) }
        compose.onNodeWithContentDescription("第 18 页").assertIsSelected()
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").performTextReplacement("1")
        compose.onNodeWithText("跳转页码").performImeAction()
        compose.runOnIdle { assertEquals(0, page) }
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
    }

    @Test fun jumpInputAnimatesClosedAndCanBeReopenedDuringTransition() {
        compose.setContent { NoveliaTheme("light") { PageControls(0, 20) {} } }
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").performTextInput("12")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("page-jump-input").assertExists()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
