@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.downloads

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.CreateBookDocument
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.ImportResultsPanel
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun DownloadsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var exportId by rememberSaveable { mutableStateOf<String?>(null) }; var remove by remember { mutableStateOf<DownloadEntry?>(null) }
    val reducedMotion = appReducedMotion()
    var importing by remember { mutableStateOf(setOf<String>()) }
    val importer = rememberDownloadImporter(c.app)
    val batch by importer.state.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val available = remember(state.downloads, importing) { state.downloads.filter { it.status == "已完成" && it.id !in importing }.map { it.id } }
    val selected = available.filter { it in selection }
    LaunchedEffect(available) { selection = selection.filter { it in available } }
    BackHandler(selecting) { selecting = false; selection = emptyList() }
    val exporter = rememberLauncherForActivityResult(CreateBookDocument()) { uri ->
        val pendingId = exportId
        exportId = null
        if(uri != null) c.action("文件已导出") {
            val entry = c.store.state.value.downloads.firstOrNull { it.id == pendingId } ?: error("下载任务已不存在，请重新选择文件")
            withContext(Dispatchers.IO) { File(c.store.downloadsDir, entry.fileName).inputStream().use { input ->
                c.app.contentResolver.openOutputStream(uri, "wt")?.use { input.copyTo(it) } ?: error("无法写入")
            } }
        }
    }
    Screen("下载管理", c::back, actions = {
        TextButton(onClick = { selecting = !selecting; selection = emptyList() },
            enabled = !batch.running && importing.isEmpty() && (selecting || available.isNotEmpty())) {
            Icon(if(selecting) Icons.Outlined.Close else Icons.Outlined.LibraryAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if(selecting) "取消" else "批量导入")
        }
    }) { padding -> Column(Modifier.padding(padding)) {
        if(selecting) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已选 ${selected.size} 个", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
            val allSelected = available.isNotEmpty() && selected.size == available.size
            TextButton(onClick = { selection = if(allSelected) emptyList() else available }, enabled = available.isNotEmpty()) {
                Text(if(allSelected) "取消全选" else "全选已完成")
            }
            FilledTonalButton(onClick = {
                importer.start(state.downloads.filter { it.id in selected })
                selecting = false
                selection = emptyList()
            }, enabled = !batch.running && importing.isEmpty() && selected.isNotEmpty(), modifier = Modifier.testTag("download-batch-import")) {
                Icon(Icons.Outlined.LibraryAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入书架（${selected.size}）")
            }
        }
        ImportResultsPanel(batch.items, batch.running, importer::pause, importer::retryFailed, importer::resume, c::book)
        AppLazyColumn(Modifier.weight(1f).testTag("downloads-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(state.downloads.isEmpty()) item { EmptyState("还没有下载任务", "在作品详情或文库分卷中下载小说，完成后可以导出或导入阅读。", Icons.Outlined.Download) }
        items(state.downloads, key = { it.id }, contentType = { "download" }) { entry ->
            var more by remember(entry.id) { mutableStateOf(false) }
            val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
            val selectionModifier = if(selecting) Modifier.testTag("download-select-${entry.id}")
                .toggleable(entry.id in selected, enabled = entry.id in available, role = Role.Checkbox) { checked ->
                    selection = if(checked) selection + entry.id else selection - entry.id
                } else Modifier
            Card(itemMotion.fillMaxWidth().animateContentSize(tween(if(reducedMotion) 0 else AppMotion.Standard)).then(selectionModifier)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if(selecting) Checkbox(entry.id in selected, onCheckedChange = null, enabled = entry.id in available)
            }
            MotionContent(entry.status, animateInitial = false) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val statusIcon = when(entry.status) { "已完成" -> Icons.Outlined.CheckCircle; "已暂停" -> Icons.Outlined.PauseCircle; "失败" -> Icons.Outlined.ErrorOutline; "等待下载" -> Icons.Outlined.Schedule; "需要登录" -> Icons.Outlined.AccountCircle; else -> Icons.Outlined.Downloading }
                    val statusColor = if(entry.status == "失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Icon(statusIcon, null, Modifier.size(18.dp), tint = statusColor)
                    Text("${entry.status}${if(entry.status == "下载中") " · ${entry.progress}%" else ""}", style = MaterialTheme.typography.labelLarge, color = statusColor)
                }
            }
            if(entry.status == "下载中") {
                val progress = animateFloatAsState((entry.progress / 100f).coerceIn(0f, 1f), tween(if(reducedMotion) 0 else AppMotion.Standard, easing = LinearEasing), label = "download progress")
                LinearProgressIndicator(progress = { if(reducedMotion) (entry.progress / 100f).coerceIn(0f, 1f) else progress.value }, modifier = Modifier.fillMaxWidth())
            }
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if(entry.status == "已暂停") Text("重新开始会从头下载此文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(!selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when(entry.status) {
                    "下载中", "等待下载" -> TextButton(onClick = { c.action { DownloadWorker.pause(c.app, entry.id) } }) { Text("暂停") }
                    "已完成" -> {
                        Button(enabled = !batch.running && entry.id !in importing, modifier = Modifier.testTag("download-read-${entry.id}"), onClick = {
                            importing = importing + entry.id
                            c.action {
                                try {
                                    c.book(importDownloadedDocument(c.app, entry).ref)
                                } finally { importing = importing - entry.id }
                            }
                        }) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(entry.id in importing) "正在准备…" else "开始阅读") }
                    }
                    else -> FilledTonalButton(onClick = {
                        val restart = { c.action { c.store.state.value.downloads.firstOrNull { it.id == entry.id }?.let { DownloadWorker.enqueue(c.app, it) } } }
                        if(entry.status == "需要登录") { c.afterLogin = restart; c.go("login") } else restart()
                    }) { Text(downloadRecoveryLabel(entry.status)) }
                }
                Box {
                    IconButton(onClick = { more = true }, enabled = !batch.running && entry.id !in importing) { Icon(Icons.Outlined.MoreVert, "更多下载操作 ${entry.title}") }
                    AppDropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if(entry.status == "已完成") {
                            DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = {
                                more = false
                                val file = File(c.store.downloadsDir, entry.fileName); val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", file)
                                val mime = bookFileMimeType(file.name)
                                runCatching { c.app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { c.message("没有找到可打开该文件的应用，可选择开始阅读") }
                            })
                            DropdownMenuItem(text = { Text("导出文件") }, onClick = { more = false; exportId = entry.id; exporter.launch(entry.fileName.substringAfter("${entry.id}-")) })
                            DropdownMenuItem(text = { Text("分享") }, onClick = {
                                more = false
                                val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", File(c.store.downloadsDir, entry.fileName))
                                c.app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(bookFileMimeType(entry.fileName)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            })
                        }
                        DropdownMenuItem(text = { Text("删除") }, onClick = { more = false; remove = entry }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                    }
                }
            }
        } } }
    } } }
    remove?.let { entry -> ConfirmDialog("删除下载？", "移除该任务及其下载文件，已导入书架的副本不受影响。", { remove = null }, confirmLabel = "删除下载") { c.action { DownloadWorker.remove(c.app, entry.id) } } }
}
