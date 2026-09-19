package cc.novelia.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.KeywordCatalog
import cc.novelia.app.data.KeywordEntry
import cc.novelia.app.ui.KeywordEditorDialog
import cc.novelia.app.ui.SearchAssistantPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchAssistantTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun assistantCollapsesAndOnlyChangesManualQueryWhenApplied() {
        var expanded by mutableStateOf(false)
        var query = "(旅人 | 少女)"
        compose.setContent {
            MaterialTheme { SearchAssistantPanel(query, KeywordCatalog.common, expanded, { expanded = it }, { query = it }, { _, _ -> }, {}, Modifier.fillMaxWidth()) }
        }
        compose.onNodeWithTag("assistant-tag-input").assertDoesNotExist()
        compose.onNodeWithTag("search-assistant-toggle").performClick()
        screenshot("search-assistant-expanded")
        compose.onNodeWithTag("assistant-tag-input").performTextInput("病娇")
        compose.onNodeWithTag("assistant-candidate-ヤンデレ").performScrollTo().performClick()
        screenshot("search-assistant-tag-alias")
        compose.onNodeWithTag("keyword-include").performClick()
        compose.runOnIdle { assertEquals("(旅人 | 少女)", query) }
        compose.onNodeWithTag("assistant-append").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("(旅人 | 少女) ヤンデレ$", query); assertFalse(expanded) }
    }

    @Test fun unknownTagCanBeManuallyExcluded() {
        var expanded by mutableStateOf(true)
        var expression = ""
        compose.setContent {
            MaterialTheme { SearchAssistantPanel("", KeywordCatalog.common, expanded, { expanded = it }, { expression = it }, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("assistant-tag-input").performTextInput("未知の世界")
        compose.onNodeWithTag("assistant-manual-tag").performScrollTo().performClick()
        compose.onNodeWithTag("keyword-exclude").performClick()
        compose.onNodeWithTag("assistant-append").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("-未知の世界$", expression) }
    }

    @Test fun tagTranslationCanBeCleared() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme { KeywordEditorDialog(KeywordEntry("ヤンデレ", "病娇"), {}, { original, translation -> saved = original to translation }) }
        }
        screenshot("search-assistant-translation-editor")
        compose.onNodeWithTag("keyword-translation").performTextClearance()
        compose.onNodeWithText("保存翻译").performClick()
        compose.runOnIdle { assertEquals("ヤンデレ" to "", saved) }
    }

    @Test fun overlongTranslationShowsAnErrorAndCannotBeSaved() {
        var saved = false
        compose.setContent {
            MaterialTheme { KeywordEditorDialog(KeywordEntry("ヤンデレ"), {}, { _, _ -> saved = true }) }
        }
        compose.onNodeWithTag("keyword-translation").performTextInput("字".repeat(KeywordCatalog.MAX_TEXT_LENGTH + 1))
        compose.onNodeWithText("原文和翻译最多 256 字符，请缩短后保存。").assertIsDisplayed()
        compose.onNodeWithText("保存翻译").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(saved) }
    }

    @Test fun persistenceFailureRemainsVisibleWithTheAssistantCollapsed() {
        compose.setContent {
            MaterialTheme { SearchAssistantPanel("", KeywordCatalog.common, false, {}, {}, { _, _ -> }, {}, persistenceError = "标签词库和翻译暂未保存，正在重试") }
        }
        compose.onNodeWithText("标签词库和翻译暂未保存，正在重试").assertIsDisplayed()
    }
}
