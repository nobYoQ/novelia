@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cc.novelia.app.ui.markdown

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 尺寸变化时保留同一输入缓冲区，包括输入法组合文本和选区。 */
@Composable internal fun MarkdownEditor(text: String, onTextChange: (String) -> Unit, maxEditorHeight: Dp, unicodeLimit: Int? = null) {
    // Unicode 软限制允许用户缩短既有超长论坛草稿，避免自动截断内容。
    val length = if(unicodeLimit != null) text.codePointCount(0, text.length) else text.length
    val limit = unicodeLimit ?: 20000
    var source by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(text)) }
    var toolbarSelection by remember(text) { mutableStateOf<TextFieldValue?>(null) }
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
    // 响应键盘引起的尺寸变化，但不在每次按键后干扰用户手动滚动。
    LaunchedEffect(focused, imeBottom, maxEditorHeight) {
        if (focused) { withFrameNanos { }; bringIntoView.bringIntoView() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正文 · 支持 Markdown", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownToolbar({
            val selected = toolbarSelection?.takeIf { it.text == current.text } ?: current
            toolbarSelection = null
            val formatted = selected.format(it, if(unicodeLimit != null) Int.MAX_VALUE else 20000)
            source = formatted
            onTextChange(formatted.text)
            focus.requestFocus()
            keyboard?.show()
        })
        OutlinedTextField(current, {
            if (unicodeLimit != null || it.text.length <= 20000) { source = it; onTextChange(it.text) }
        }, modifier = Modifier.fillMaxWidth().heightIn(max = inputHeight).testTag("article-body")
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if(it.isFocused) toolbarSelection = null }
            .onPreviewKeyEvent {
                // 键盘焦点移出时，先保存选区，再允许 TextField 清除选择。
                if(it.type == KeyEventType.KeyDown && it.key == Key.Tab && !it.isCtrlPressed && !it.isAltPressed && !it.isMetaPressed) toolbarSelection = current
                false
            },
            placeholder = { Text("写下你的想法…") }, minLines = minOf(6, visibleLines), maxLines = visibleLines,
            isError = length > limit, textStyle = MaterialTheme.typography.bodyLarge)
        }
        Text("$length / $limit", style = MaterialTheme.typography.bodySmall, color = if(length > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
