package cc.novelia.app.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeywordLimitPreferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customLimitRejectsInvalidInputAndExplainsPreservingExistingTags() {
        var saved by mutableStateOf<Int?>(null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            KeywordLimitPreference(saved, 20_681) { saved = it }
        } } }
        compose.onNodeWithText("标签数量上限").assertIsDisplayed()
        compose.onNodeWithText("不限").assertIsSelected()
        compose.onNodeWithText("自定义").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextInput("0")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextReplacement("99999999999")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextReplacement("10000")
        compose.onNodeWithText("当前已有 20681 个标签，设置此上限会保留已有标签并暂停新增。").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(10_000, saved) }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        val input = compose.onNodeWithTag("keyword-limit-input")
        input.performTextReplacement("30000")
        input.performImeAction()
        compose.runOnIdle { assertEquals(30_000, saved) }
        compose.onNodeWithText("不限").performClick()
        compose.onNodeWithTag("keyword-limit-input").assertDoesNotExist()
        compose.runOnIdle { assertNull(saved) }
    }
}
