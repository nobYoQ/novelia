package cc.novelia.app.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MarkdownLinkPasteTest {
    @get:Rule val compose = createComposeRule()
    private var clip: ClipEntry? = null
    private var clipboardWrites = 0
    private var savedDraft = ""
    private val original = "前文选中内容后文"

    private fun clipboard(text: String) { clip = ClipEntry(ClipData.newPlainText("测试", text)) }
    private fun showEditor(comment: Boolean, limit: Int? = null) {
        compose.setContent {
            val system = LocalClipboard.current
            val testClipboard = remember(system) { object : Clipboard by system {
                override suspend fun getClipEntry() = clip
                override suspend fun setClipEntry(clipEntry: ClipEntry?) { clip = clipEntry; clipboardWrites++ }
            } }
            CompositionLocalProvider(LocalClipboard provides testClipboard) {
                NoveliaTheme("light") {
                    var text by remember { mutableStateOf(original) }
                    var title by remember { mutableStateOf("标题") }
                    Column(Modifier.fillMaxSize()) {
                        OutlinedTextField(title, { title = it }, modifier = Modifier.testTag("plain-title"))
                        val save: (String) -> Unit = { text = it; savedDraft = it }
                        if(comment) MarkdownCommentInput(text, save, "评论", softLimit = true, unicodeLimit = limit ?: 1000) {}
                        else MarkdownEditor(text, save, 420.dp, unicodeLimit = limit)
                    }
                }
            }
        }
    }

    private fun field(comment: Boolean = false) = compose.onNodeWithTag(if(comment) "comment-body" else "article-body")
    private fun SemanticsNodeInteraction.select(range: TextRange) = apply { performTextInputSelection(range) }
    private fun SemanticsNodeInteraction.replace(text: String) = apply { performTextReplacement(text) }
    private fun SemanticsNodeInteraction.paste() = performSemanticsAction(SemanticsActions.PasteText) { it() }
    private fun SemanticsNodeInteraction.control(key: Key, shift: Boolean = false) = performKeyInput {
        keyDown(Key.CtrlLeft)
        if(shift) keyDown(Key.ShiftLeft)
        pressKey(key)
        if(shift) keyUp(Key.ShiftLeft)
        keyUp(Key.CtrlLeft)
    }

    @Test fun articlePasteKeepsCursorUndoRedoAndTheOriginalClipboard() {
        val url = "https://example.com/a(b)?q=one#part"
        val link = "[选中内容](https://example.com/a%28b%29?q=one#part)"
        clipboard(url); showEditor(comment = false)
        field().select(TextRange(6, 2)).paste()
        field().assertTextContains("前文${link}后文").assertIsFocused()
        assertEquals(TextRange(2 + link.length), field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field().control(Key.Z)
        field().assertTextContains(original)
        field().control(Key.Z, shift = true)
        field().assertTextContains("前文${link}后文")
        compose.runOnIdle {
            assertEquals("前文${link}后文", savedDraft)
            assertEquals(url, clip!!.clipData.getItemAt(0).text.toString())
            assertEquals(0, clipboardWrites)
        }
        compose.onNodeWithTag("plain-title").select(TextRange(0, 2)).paste()
            .assertTextContains(url)
        field().assertTextContains("前文${link}后文")
    }

    @Test fun commentKeyboardPasteUpdatesTheDraftAndCopyStillUsesTheClipboard() {
        clipboard("https://example.com"); showEditor(comment = true)
        val expected = "前文[选中内容](https://example.com/)后文"
        field(true).select(TextRange(2, 6)).control(Key.V)
        field(true).assertTextContains(expected)
        compose.runOnIdle { assertEquals(expected, savedDraft); assertEquals(0, clipboardWrites) }
        field(true).select(TextRange(0, expected.length))
            .performSemanticsAction(SemanticsActions.CopyText) { it() }
        compose.runOnIdle { assertEquals(expected, clip!!.clipData.getItemAt(0).text.toString()); assertEquals(1, clipboardWrites) }
    }

    @Test fun unsupportedInputAndCollapsedSelectionsKeepNormalPasteBehavior() {
        clipboard("普通文字"); showEditor(comment = false)
        field().select(TextRange(2, 6)).paste().assertTextContains("前文普通文字后文")
        compose.runOnIdle { clipboard("https://example.com/") }
        field().replace("多\n行").select(TextRange(0, 3)).paste()
            .assertTextContains("https://example.com/")
        field().replace("光标").select(TextRange(2)).paste()
            .assertTextContains("光标https://example.com/")
        field().replace("键盘输入").select(TextRange(0, 4))
            .performTextInput("https://example.com/")
        field().assertTextContains("https://example.com/")
        compose.runOnIdle { assertEquals("https://example.com/", savedDraft) }
    }

    @Test fun linkOverTheCommentLimitFallsBackToTheUnmodifiedPastedText() {
        clipboard("http://a"); showEditor(comment = true, limit = 10)
        field(true).replace("前字后").select(TextRange(1, 2)).paste()
            .assertTextContains("前http://a后")
        compose.runOnIdle { assertEquals("前http://a后", savedDraft) }
    }
}
