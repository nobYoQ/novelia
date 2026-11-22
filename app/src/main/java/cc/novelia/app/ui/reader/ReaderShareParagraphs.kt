package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.annotationLabel
import cc.novelia.app.reader.annotationSource
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppTextButton

internal data class ParagraphShareStart(val index: Int, val text: String, val source: String, val occurrence: Int)
internal data class ShareableParagraph(val index: Int, val text: String, val version: String)

internal fun shareableParagraphs(versions: List<ReadingParagraph>, visible: List<ReadingParagraph>, start: ParagraphShareStart,
    checkCancelled: () -> Unit): List<ShareableParagraph> {
    val visibleByIndex = visible.associateBy(ReadingParagraph::index)
    return versions.mapNotNull { paragraph ->
        checkCancelled()
        if (paragraph.imageUrl != null || paragraph.localImageId != null) return@mapNotNull null
        val matching = paragraph.parts.filter { annotationSource(it) == start.source }.getOrNull(start.occurrence)
        val part = matching ?: visibleByIndex[paragraph.index]?.parts?.firstOrNull() ?: paragraph.parts.firstOrNull()
        val text = if (paragraph.index == start.index) start.text else part?.text.orEmpty()
        if (part == null || part.source == "插图" || text.isBlank()) null
        else ShareableParagraph(paragraph.index, text, annotationLabel(part, if (matching != null) start.occurrence else 0))
    }
}

@Composable internal fun ReaderShareParagraphs(paragraphs: List<ShareableParagraph>?, initialIndex: Int,
    onDismiss: () -> Unit, onPreview: (String) -> Unit) {
    var selected by remember(initialIndex) { mutableStateOf(setOf(initialIndex)) }
    ReaderSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 20.dp)) {
            Text("选择要分享的段落", style = MaterialTheme.typography.titleLarge)
            Text("勾选本章段落，图片会按正文顺序排版。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (paragraphs == null) {
                Text("正在整理本章段落…", Modifier.weight(1f))
            } else {
                val scroll = rememberLazyListState(paragraphs.indexOfFirst { it.index == initialIndex }.coerceAtLeast(0))
                AppLazyColumn(Modifier.weight(1f).testTag("share-paragraphs"), state = scroll) {
                    items(paragraphs, key = { it.index }) { paragraph ->
                        val checked = paragraph.index in selected
                        Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = {
                            selected = if (it) selected + paragraph.index else selected - paragraph.index
                        }).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AppCheckbox(checked, null)
                            Column(Modifier.weight(1f)) {
                                Text("第 ${paragraph.index + 1} 段 · ${paragraph.version}", style = MaterialTheme.typography.labelMedium)
                                Text(paragraph.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End) {
                AppTextButton(onDismiss) { Text("取消") }
                AppButton(onClick = {
                    val text = paragraphs.orEmpty().filter { it.index in selected }.joinToString("\n\n") { it.text }
                    if (text.isNotBlank()) onPreview(text)
                }, enabled = paragraphs?.any { it.index in selected } == true) { Text("预览 · ${selected.size} 段") }
            }
        }
    }
}
