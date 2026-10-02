package cc.novelia.app.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeywordLimitPreferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customLimitRejectsInvalidInputAndExplainsPreservingExistingTags() {
        var saved: Int? = null
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            KeywordLimitDialog(null, 20_681, {}, { saved = it })
        } } }
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextInput("0")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextReplacement("99999999999")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("keyword-limit-input").performTextReplacement("10000")
        compose.onNodeWithText("已有标签超过此上限，仍会全部保留，并可继续修改译名和分类。").assertExists()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(10_000, saved) }
        compose.onNodeWithText("不限").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertNull(saved) }
    }
}
