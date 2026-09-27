package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PageControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun numberedPagesAndValidatedJumpUseZeroBasedCallbacks() {
        var page by mutableIntStateOf(0)
        compose.setContent { NoveliaTheme("light") {
            Box(Modifier.width(360.dp)) { PageControls(page, 35) { page = it } }
        } }
        compose.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第 35 页").performClick()
        compose.runOnIdle { assertEquals(34, page) }
        compose.onNodeWithContentDescription("下一页").assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextInput("0")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("36")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("18")
        compose.onNodeWithText("跳转", substring = false).performClick()
        compose.runOnIdle { assertEquals(17, page) }
        compose.onNodeWithContentDescription("第 18 页").assertIsSelected()
        compose.onNodeWithText("跳转页码").performTextReplacement("1")
        compose.onNodeWithText("跳转页码").performImeAction()
        compose.runOnIdle { assertEquals(0, page) }
    }
}
