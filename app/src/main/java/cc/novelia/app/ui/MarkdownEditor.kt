@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep one source buffer, including the IME composition and selection, while resizing. */
@Composable internal fun MarkdownEditor(text: String, onTextChange: (String) -> Unit, maxEditorHeight: Dp) {
    var source by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(text)) }
    val current = if (source.text == text) source else TextFieldValue(text,
        TextRange(source.selection.start.coerceAtMost(text.length), source.selection.end.coerceAtMost(text.length)))
    val bringIntoView = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val lineHeight = with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    val inputHeight = (maxEditorHeight - 56.dp).coerceAtLeast(56.dp)
    val visibleLines = ((inputHeight - 32.dp) / lineHeight).toInt().coerceAtLeast(1)
    // Respond to keyboard resizing, without fighting manual scrolling on each keystroke.
    LaunchedEffect(focused, imeBottom, maxEditorHeight) {
        if (focused) { withFrameNanos { }; bringIntoView.bringIntoView() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正文 · 支持 Markdown", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownToolbar({
            val formatted = current.format(it, 20000)
            source = formatted
            onTextChange(formatted.text)
            focus.requestFocus()
            keyboard?.show()
        })
        OutlinedTextField(current, {
            if (it.text.length <= 20000) { source = it; onTextChange(it.text) }
        }, modifier = Modifier.fillMaxWidth().heightIn(max = inputHeight).testTag("article-body")
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
            placeholder = { Text("写下你的想法…") }, minLines = minOf(6, visibleLines), maxLines = visibleLines,
            textStyle = MaterialTheme.typography.bodyLarge)
        }
        Text("${text.length} / 20000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
