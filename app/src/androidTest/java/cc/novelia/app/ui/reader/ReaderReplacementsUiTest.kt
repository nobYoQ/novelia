package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderReplacementsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rulesCanBeAddedPreviewedDisabledEditedAndDeleted() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            ReaderReplacementsSheet(settings, "爱丽丝来到小镇。", {}, { settings = it })
        } } }
        compose.onNodeWithText("添加规则").performClick()
        compose.onNodeWithTag("reader-replacement-source").performTextInput("爱丽丝")
        compose.onNodeWithTag("reader-replacement-target").performTextInput("艾莉丝")
        compose.onNodeWithText("保存到本书").performClick()
        compose.runOnIdle { assertEquals(mapOf("爱丽丝" to "艾莉丝"), settings.textReplacements) }
        compose.onNodeWithText("预览当前段").performClick()
        compose.onNodeWithTag("reader-replacement-preview").assertTextContains("显示为：艾莉丝来到小镇。")
        compose.onNodeWithText("启用文字替换").performClick()
        compose.onNodeWithTag("reader-replacement-preview").assertTextContains("显示为：爱丽丝来到小镇。")
        compose.onNodeWithContentDescription("编辑 爱丽丝").performScrollTo().performClick()
        compose.onNodeWithTag("reader-replacement-target").performTextReplacement("艾莉西亚")
        compose.onNodeWithText("保存到本书").performClick()
        compose.onNodeWithContentDescription("删除 爱丽丝").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.textReplacements.isEmpty()) }
    }
}
