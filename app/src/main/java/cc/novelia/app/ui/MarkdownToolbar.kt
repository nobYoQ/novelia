package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.MarkdownTemplate
import cc.novelia.app.data.applyMarkdownTemplate

internal fun TextFieldValue.format(template: MarkdownTemplate, limit: Int): TextFieldValue {
    val edit = applyMarkdownTemplate(text, selection.start, selection.end, template, limit)
    return if (edit.text == text && edit.selectionStart == selection.start && edit.selectionEnd == selection.end) this
        else TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))
}

@Composable internal fun MarkdownToolbar(onFormat: (MarkdownTemplate) -> Unit, modifier: Modifier = Modifier) {
    var help by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.appHorizontalScroll(rememberScrollState())) {
            MarkdownTemplate.entries.forEach { template ->
                IconButton(onClick = { onFormat(template) }, modifier = Modifier.size(48.dp)) {
                    Icon(when (template) {
                        MarkdownTemplate.Bold -> Icons.Outlined.FormatBold
                        MarkdownTemplate.Italic -> Icons.Outlined.FormatItalic
                        MarkdownTemplate.Strike -> Icons.Outlined.StrikethroughS
                        MarkdownTemplate.Link -> Icons.Outlined.Link
                        MarkdownTemplate.Spoiler -> Icons.Outlined.WarningAmber
                        MarkdownTemplate.Star -> Icons.Outlined.StarOutline
                        MarkdownTemplate.Details -> Icons.AutoMirrored.Outlined.MenuOpen
                    }, "插入${template.label}")
                }
            }
            IconButton(onClick = { help = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.HelpOutline, "Markdown 格式帮助")
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Markdown 格式帮助") },
        text = {
            AppScrollColumn(contentModifier = Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选中文字后点击图标套用格式，再次点击可取消。评分数字可改为 0～5，支持半星；折叠内容默认收起。")
                SelectionContainer {
                    Text("**粗体**\n*斜体*\n~~删除线~~\n[文字](https://n.novelia.cc/)\n!!剧透!!\n\n::: star 4.5\n\n::: details 点击展开\n折叠内容\n:::\n\n# 标题\n\n- 无序列表\n1. 有序列表\n\n> 引用\n\n---\n\n![图片说明](图片链接)\n\n| 左对齐 | 居中 | 右对齐 |\n| :- | :-: | -: |\n| 文本 | 文本 | 文本 |", fontFamily = FontFamily.Monospace)
                }
            }
        }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

@Composable internal fun MarkdownCommentInput(text: String, onTextChange: (String) -> Unit, label: String, sendButton: @Composable () -> Unit) {
    var source by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(text)) }
    var toolbarSelection by remember(text) { mutableStateOf<TextFieldValue?>(null) }
    val current = if (source.text == text) source else TextFieldValue(text, TextRange(source.selection.start.coerceAtMost(text.length), source.selection.end.coerceAtMost(text.length)))
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun change(value: TextFieldValue) { if (value.text.length <= 10000) { source = value; onTextChange(value.text) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownToolbar({
            val selected = toolbarSelection?.takeIf { it.text == current.text } ?: current
            toolbarSelection = null
            change(selected.format(it, 10000))
            focus.requestFocus()
            keyboard?.show()
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(current, ::change, label = { Text(label) }, modifier = Modifier.weight(1f).focusRequester(focus).testTag("comment-body")
                .onFocusChanged { if(it.isFocused) toolbarSelection = null }
                .onPreviewKeyEvent {
                    // TextField collapses selection on blur. Capture it before Tab transfers focus.
                    if(it.type == KeyEventType.KeyDown && it.key == Key.Tab && !it.isCtrlPressed && !it.isAltPressed && !it.isMetaPressed) toolbarSelection = current
                    false
                }, maxLines = 4)
            sendButton()
        }
    }
}
