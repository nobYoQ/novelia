package cc.novelia.app.ui.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderAnnotationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactSelectionAndVersionSwitchUseTheChosenText() {
        val visible = ReadingParagraph(4, listOf(TextPart("前文 喜欢的句子 后文", "gpt")))
        val all = visible.copy(parts = listOf(TextPart("日本語の原文", "日文")) + visible.parts)
        var shared = ""
        var lookup = ""
        var saved: Pair<String, NoteSelection>? = null
        compose.setContent {
            NoveliaTheme("light") { AppInteractionMode(false, true) {
                ReaderParagraphSheet(visible, all, emptyList(), {}, {}, { shared = it }, { lookup = it }, {},
                    { quote, range, _ -> saved = quote to range }, {}, {}, {})
            } }
        }
        compose.onNodeWithText("划线").assertIsNotEnabled()
        compose.onNodeWithTag("reader-selection-text").performTextInputSelection(TextRange(3, 8))
        compose.onNodeWithText("分享选文").performScrollTo().performClick()
        compose.onNodeWithText("查词 / 翻译").performScrollTo().performClick()
        compose.onNodeWithText("划线").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("喜欢的句子", shared)
            assertEquals(shared, lookup)
            assertEquals(shared to NoteSelection("gpt", start = 3, end = 8), saved)
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = java.io.File(instrumentation.targetContext.getExternalFilesDir("reader-screenshots"), "selection-panel.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("reader-version-0").performScrollTo().performClick()
        compose.onNodeWithTag("reader-selection-text").assertTextContains("日本語の原文")
        compose.onNodeWithText("写批注").assertIsNotEnabled()
        compose.onNodeWithTag("reader-selection-text").performTextInputSelection(TextRange(0, 3))
        compose.onNodeWithText("写批注").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("日本語" to NoteSelection("original", start = 0, end = 3), saved) }
    }
}
