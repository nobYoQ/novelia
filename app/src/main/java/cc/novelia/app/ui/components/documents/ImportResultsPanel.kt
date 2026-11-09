@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components.documents

import cc.novelia.app.ui.components.base.AppLinearProgressIndicator
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.importing.ImportItem
import cc.novelia.app.files.importing.ImportStatus
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appShape
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppSheet

@Composable internal fun ImportResultsPanel(
    items: List<ImportItem>, running: Boolean, onPause: () -> Unit, onRetryFailed: () -> Unit,
    onResume: () -> Unit, onRead: (BookRef) -> Unit, onDismiss: () -> Unit, error: String? = null,
    detailsOpen: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
) {
    var open by detailsOpen
    if(items.isEmpty()) {
        LaunchedEffect(Unit) { open = false }
        return
    }
    val reducedMotion = appReducedMotion()
    val completed = items.count { it.status in setOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed) }
    val statusCounts = listOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed, ImportStatus.Pending)
        .associateWith { status -> items.count { it.status == status } }
    val counts = statusCounts.entries.joinToString(" · ") { (status, count) -> "${status.label} $count" }
    val summary = statusCounts.filterValues { it > 0 }.entries.joinToString(" · ") { (status, count) -> "${status.label} $count" }
    val failed = statusCounts.getValue(ImportStatus.Failed) > 0 || error != null
    val pending = statusCounts.getValue(ImportStatus.Pending) > 0
    val title = when {
        running -> "正在导入 $completed/${items.size}"
        error != null -> "导入中断"
        pending -> "导入已暂停"
        failed -> "导入完成 · 有失败项"
        items.all { it.status == ImportStatus.Duplicate } -> "文件已在书架中"
        else -> "导入完成"
    }
    val subtitle = when {
        running -> items.firstOrNull { it.status == ImportStatus.Running }?.let { "${it.name} · ${it.detail}" } ?: "正在准备文件…"
        error != null -> error
        items.size == 1 && !failed && !pending -> items.single().name
        else -> summary
    }
    val accent = if(failed && !running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val readBook: (BookRef) -> Unit = { ref ->
        open = false
        if(!running && !failed && !pending) onDismiss()
        onRead(ref)
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("import-status"),
        shape = appShape(MaterialTheme.shapes.medium), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "查看导入结果") { open = true }
                    .testTag("import-results").padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(when {
                        running -> Icons.Outlined.FileDownload
                        failed -> Icons.Outlined.ErrorOutline
                        pending -> Icons.Outlined.PauseCircleOutline
                        else -> Icons.Outlined.CheckCircle
                    }, null, Modifier.size(22.dp), tint = accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if(running) {
                    AppIconButton(onClick = onPause) { Icon(Icons.Outlined.PauseCircleOutline, "暂停导入") }
                } else {
                    items.singleOrNull()?.bookKey?.let { key ->
                        AppTextButton(onClick = { readBook(BookRef.fromKey(key)) }) { Text("阅读") }
                    }
                    AppIconButton(onClick = { open = false; onDismiss() }) { Icon(Icons.Outlined.Close, "关闭导入结果") }
                }
            }
            if(running) AppLinearProgressIndicator(progress = { completed.toFloat() / items.size }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
    if(open) AppSheet(onDismissRequest = { open = false }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("导入详情", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            AppIconButton(onClick = { open = false }) { Icon(Icons.Outlined.Close, "收起导入详情") }
        }
        Text(counts, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
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
                    item.bookKey?.let { key -> AppTextButton(onClick = { readBook(BookRef.fromKey(key)) }) { Text("开始阅读") } }
                }
                HorizontalDivider()
            }
        }
    }
}
