package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderIndentPreferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customInputCommitsOnlyValidIntegersAndCanDisableOrRestoreIndent() {
        var selected by mutableIntStateOf(2)
        compose.setContent { NoveliaTheme("light") {
            Surface(Modifier.width(320.dp)) {
                ReaderIndentPreference(selected, 1) { selected = it }
            }
        } }
        val input = compose.onNodeWithTag("reader-indent-input")
        val apply = compose.onNodeWithTag("reader-indent-apply")
        input.performTextReplacement("7")
        compose.runOnIdle { assertEquals(2, selected) }
        apply.performClick()
        compose.runOnIdle { assertEquals(7, selected) }
        for(invalid in listOf("", "-1", "21", "1.5", "abc", "999999999999")) {
            input.performTextReplacement(invalid)
            apply.assertIsNotEnabled()
            compose.runOnIdle { assertEquals(7, selected) }
        }
        input.performTextReplacement("0")
        input.performImeAction()
        compose.runOnIdle { assertEquals(0, selected) }
        compose.onNodeWithTag("reader-indent-default").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        input.assertTextContains("1")
    }

    @Test fun pagedTextUsesCustomIndentForEveryLanguagePart() {
        val paragraph = ReadingParagraph(0, listOf(TextPart("原文", "jp"), TextPart("译文", "gpt")))
        for(size in listOf(0, 1, 7)) {
            val settings = ReaderSettings(indentSize = size, parallel = true)
            val layout = measureEInkChapter(listOf(paragraph), settings, 400, 600, 1f, 1f).layouts.getValue(0)
            assertEquals("　".repeat(size) + "原文\nGPT\n" + "　".repeat(size) + "译文", layout.text.toString())
        }
    }
}
