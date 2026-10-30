@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.base.AppScrollColumn


@Composable internal fun ReaderParagraphSheet(
    paragraph: ReadingParagraph, versions: ReadingParagraph?, notes: List<Note>, onDismiss: () -> Unit,
    onCopy: (String) -> Unit, onShare: (String) -> Unit, onLookup: (String) -> Unit,
    onBookmark: () -> Unit, onAnnotate: (String, NoteSelection, Boolean) -> Unit,
    onEditNote: (Note) -> Unit, onDeleteNote: (Note) -> Unit,
    onNotes: () -> Unit,
    onSpeak: ((String, NoteSelection) -> Unit)? = null,
    onReplace: ((String) -> Unit)? = null,
) {
    val content = versions ?: paragraph
    var selectedPart by remember(content) { mutableIntStateOf(content.parts.indexOfFirst {
        annotationSource(it) == paragraph.parts.firstOrNull()?.let(::annotationSource)
    }.coerceAtLeast(0)) }
    ReaderSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(modifier = Modifier.fillMaxWidth().testTag("reader-paragraph-sheet"), contentModifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选文与对照", style = MaterialTheme.typography.titleLarge)
            if(versions == null) { Text("正在整理本段原文与译文…"); return@AppScrollColumn }
            Text("长按文字并拖动选区，复制、查词或记录批注。切换下方版本可查看本段原文与已有译文。", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                content.parts.forEachIndexed { index, part ->
                    val occurrence = content.parts.take(index).count { annotationSource(it) == annotationSource(part) }
                    FilterChip(selectedPart == index, { selectedPart = index }, label = { Text(annotationLabel(part, occurrence)) },
                        modifier = Modifier.testTag("reader-version-$index").heightIn(min = 48.dp))
                }
            }
            content.parts.getOrNull(selectedPart)?.let { part -> key(selectedPart, part.text) {
                var value by remember { mutableStateOf(TextFieldValue(part.text)) }
                val start = value.selection.min
                val end = value.selection.max
                val quote = part.text.substring(start, end)
                val selection = selectionFor(content, selectedPart, start, end)
                OutlinedTextField(value, { value = value.copy(selection = it.selection) }, readOnly = true,
                    minLines = 3, maxLines = 9, textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().testTag("reader-selection-text"))
                Text(if(selection == null) "尚未选择文字" else "已选择 ${quote.codePointCount(0, quote.length)} 字",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("reader-selection-count"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(onClick = { value = value.copy(selection = TextRange(0, part.text.length)) }) { Text("全选") }
                    AppTextButton(onClick = { onCopy(quote) }, enabled = selection != null) { Text("复制选文") }
                    AppTextButton(onClick = { onShare(quote) }, enabled = selection != null) { Text("分享选文") }
                    AppTextButton(onClick = { onLookup(quote) }, enabled = selection != null && quote.length <= 2000) { Text("查词 / 翻译") }
                    AppTextButton(onClick = { selection?.let { onAnnotate(quote, it, false) } }, enabled = selection != null && quote.length <= 100_000) { Text("划线") }
                    AppTextButton(onClick = { selection?.let { onAnnotate(quote, it, true) } }, enabled = selection != null && quote.length <= 100_000) { Text("写批注") }
                    if(onSpeak != null) AppTextButton(onClick = { selection?.let { onSpeak(quote, it) } }, enabled = selection != null && quote.length <= 100_000) { Text("朗读选文") }
                    if(onReplace != null) AppTextButton(onClick = { onReplace(quote) }, enabled = selection != null && quote.length <= cc.novelia.app.data.model.MAX_READER_REPLACEMENT_LENGTH) { Text("替换选文") }
                }
                if(quote.length > 2000) Text("查词请选取不超过 2000 字的文字。", style = MaterialTheme.typography.bodySmall)
            } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextButton(onClick = onBookmark, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存书签") }
                AppTextButton(onClick = onNotes, modifier = Modifier.heightIn(min = 48.dp)) { Text("本书书签与批注") }
                AppTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续阅读") }
            }
            if(notes.isNotEmpty()) {
                HorizontalDivider()
                Text("本段书签与批注", style = MaterialTheme.typography.titleSmall)
                notes.forEach { note ->
                    Text(note.quote, maxLines = 3)
                    if(note.text.isNotBlank()) Text(note.text, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextButton(onClick = { onEditNote(note) }) { Text("编辑笔记") }
                        AppTextButton(onClick = { onDeleteNote(note) }) { Text("删除记录") }
                    }
                }
            }
        }
    }
}
