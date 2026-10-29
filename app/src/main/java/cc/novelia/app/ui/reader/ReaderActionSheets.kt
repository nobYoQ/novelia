@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.TogglePreference
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.appVerticalScroll
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerAccent


/** 弹层不持有阅读位置或异步任务；关闭、朗读及书签操作交回页面。 */
@Composable internal fun ReaderSpeechSheet(
    settings: ReaderSettings, speechStatus: String, onDismiss: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onStop: () -> Unit, onSettings: () -> Unit,
    playback: SpeechPlayback? = null, onPrevious: () -> Unit = {}, onNext: () -> Unit = {}, onFollow: (Boolean) -> Unit = {},
) {
    ReaderSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("系统朗读", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if(speechStatus == ReadAloudService.SLEEP_TIMER_FINISHED) StickerAccent(MidoriSticker.Sleep, speechStatus, Modifier.size(64.dp))
                Text(speechStatus.ifBlank { if(settings.speechContinueChapters) "从当前段落开始，自动连续朗读后续章节。语音由系统提供。" else "从当前段落朗读至本章结束。语音由系统提供。" }, style = MaterialTheme.typography.bodyMedium)
            }
            Text("${settings.speechRate}× · ${settings.speechMinutes} 分钟后停止", style = MaterialTheme.typography.labelLarge)
            if(playback == null && settings.speechContinueChapters) Text(if(settings.speechNetworkContinuation) "连续听书 · 优先本地与缓存，未缓存章节自动联网加载" else "连续听书 · 仅本地与缓存章节", style = MaterialTheme.typography.bodySmall)
            playback?.takeIf { it.count > 0 }?.let { current ->
                Text("第 ${(current.index + 1).coerceAtMost(current.count)} / ${current.count} 句", Modifier.testTag("reader-speech-sentence"))
                current.segment?.let { Text(it.text, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
                TogglePreference("跟随朗读位置", "高亮当前句并滚动或翻页跟随；手动操作正文会暂停跟随", current.following, onFollow)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTextButton(onClick = onPrevious, enabled = current.index in 1 until current.count) { Text("上一句") }
                    AppTextButton(onClick = onNext, enabled = current.index + 1 < current.count) { Text("下一句") }
                }
            }
            AppButton(onClick = onStart, Modifier.fillMaxWidth(), enabled = speechStatus != "正在准备朗读…") { Text(if(speechStatus == "正在准备朗读…") "正在准备朗读…" else "从这里开始朗读") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(onClick = onPause, enabled = speechStatus.startsWith("正在朗读") || speechStatus == "正在准备下一章…") { Text("暂停") }
                AppTextButton(onClick = onResume, enabled = speechStatus == "朗读已暂停" || speechStatus.startsWith("续章已暂停")) { Text("继续") }
                AppTextButton(onClick = onStop) { Text("停止") }
                AppTextButton(onClick = onSettings) { Text("设置") }
                if(playback != null) AppTextButton(onClick = onDismiss) { Text("边听边读") }
            }
        }
    }
}

@Composable internal fun ReaderParagraphSheet(
    paragraph: ReadingParagraph, versions: ReadingParagraph?, notes: List<Note>, onDismiss: () -> Unit,
    onCopy: (String) -> Unit, onShare: (String) -> Unit, onLookup: (String) -> Unit,
    onBookmark: () -> Unit, onAnnotate: (String, NoteSelection, Boolean) -> Unit,
    onEditNote: (Note) -> Unit, onDeleteNote: (Note) -> Unit,
    onNotes: () -> Unit,
    onSpeak: ((String, NoteSelection) -> Unit)? = null,
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
