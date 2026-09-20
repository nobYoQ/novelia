package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry

@Composable
fun KeywordEditorDialog(
    entry: KeywordEntry,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onInclude: (() -> Unit)? = null,
    onExclude: (() -> Unit)? = null,
) {
    var translation by rememberSaveable(entry.original) { mutableStateOf(entry.translation) }
    val tooLong = translation.length > KeywordCatalog.MAX_TEXT_LENGTH || entry.original.length > KeywordCatalog.MAX_TEXT_LENGTH
    fun saveChanges() { if(translation != entry.translation) onSave(entry.original, translation) }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(entry.original) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(translation, { translation = it }, label = { Text("中文翻译（可留空）") }, isError = tooLong,
                    supportingText = { Text(if(tooLong) "原文和翻译最多 ${KeywordCatalog.MAX_TEXT_LENGTH} 字符，请缩短后保存。" else "${translation.length} / ${KeywordCatalog.MAX_TEXT_LENGTH}") },
                    modifier = Modifier.fillMaxWidth().testTag("keyword-translation"))
                Text("翻译只用于本机显示和联想，搜索仍使用标签原文。", style = MaterialTheme.typography.bodySmall)
                if(onInclude != null && onExclude != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { saveChanges(); onInclude() }, enabled = !tooLong && KeywordCatalog.canSearch(entry.original), modifier = Modifier.weight(1f).testTag("keyword-include")) { Text("包含") }
                        OutlinedButton(onClick = { saveChanges(); onExclude() }, enabled = !tooLong && KeywordCatalog.canSearch(entry.original), modifier = Modifier.weight(1f).testTag("keyword-exclude")) { Text("排除") }
                    }
                    if(!KeywordCatalog.canSearch(entry.original)) Text("此标签包含原站语法不支持的字符，请使用普通关键词搜索。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(entry.original, translation); onDismiss() }, enabled = !tooLong) { Text("保存翻译") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
