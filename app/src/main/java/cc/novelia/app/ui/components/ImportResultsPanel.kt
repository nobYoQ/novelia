@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import cc.novelia.app.ui.components.AppLinearProgressIndicator

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.ImportItem
import cc.novelia.app.files.ImportStatus
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun ImportResultsPanel(
    items: List<ImportItem>, running: Boolean, onPause: () -> Unit, onRetryFailed: () -> Unit,
    onResume: () -> Unit, onRead: (BookRef) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    if(items.isEmpty()) return
    val reducedMotion = appReducedMotion()
    val completed = items.count { it.status in setOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed) }
    val counts = listOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed, ImportStatus.Pending)
        .joinToString(" · ") { status -> "${status.label} ${items.count { it.status == status }}" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        AppTextButton(onClick = { open = true }, modifier = Modifier.testTag("import-results")) {
            Text("${if(running) "正在导入" else "导入清单"} $completed/${items.size} · 查看结果")
        }
        if(running) {
            items.firstOrNull { it.status == ImportStatus.Running }?.let { Text("${it.name} · ${it.detail}", maxLines = 2, style = MaterialTheme.typography.bodySmall) }
            AppLinearProgressIndicator(progress = { completed.toFloat() / items.size }, modifier = Modifier.fillMaxWidth())
        } else Text(counts, style = MaterialTheme.typography.bodySmall)
    }
    if(open) AppSheet(onDismissRequest = { open = false }) {
        Text("批量导入结果", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text(counts, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if(running) AppTextButton(onClick = onPause) { Text("暂停导入") }
            else {
                AppTextButton(onClick = onRetryFailed, enabled = items.any { it.status == ImportStatus.Failed }) { Text("仅重试失败项") }
                if(items.any { it.status == ImportStatus.Pending }) AppTextButton(onClick = onResume) { Text("继续尚未处理项") }
            }
        }
        AppLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("import-result-list")) {
            itemsIndexed(items, key = { index, _ -> index }) { index, item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${item.name}", style = MaterialTheme.typography.titleSmall)
                    Text("${item.status.label}${if(item.detail.isBlank()) "" else " · ${item.detail}"}", color = if(item.status == ImportStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(item.status == ImportStatus.Running && !reducedMotion) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    item.bookKey?.let { key -> AppTextButton(onClick = { open = false; onRead(BookRef.fromKey(key)) }) { Text("开始阅读") } }
                }
                HorizontalDivider()
            }
        }
    }
}
