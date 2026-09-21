@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.downloads

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.EmptyState
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
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pendingId = exportId
        exportId = null
        if(uri != null) c.action("文件已导出") {
            val entry = c.store.state.value.downloads.firstOrNull { it.id == pendingId } ?: error("下载任务已不存在，请重新选择文件")
            withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(uri)?.use { output -> File(c.store.downloadsDir, entry.fileName).inputStream().use { it.copyTo(output) } } ?: error("无法写入") }
        }
    }
    Screen("下载管理", c::back) { padding -> AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(state.downloads.isEmpty()) item { EmptyState("还没有下载任务", "在作品详情或文库分卷中下载小说，完成后可以导出或导入阅读。", Icons.Outlined.Download) }
        items(state.downloads, key = { it.id }, contentType = { "download" }) { entry ->
            var more by remember(entry.id) { mutableStateOf(false) }
            val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
            Card(itemMotion.fillMaxWidth().animateContentSize(tween(if(reducedMotion) 0 else AppMotion.Standard))) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when(entry.status) {
                    "下载中", "等待下载" -> TextButton(onClick = { c.action { DownloadWorker.pause(c.app, entry.id) } }) { Text("暂停") }
                    "已完成" -> {
                        Button(enabled = entry.id !in importing, modifier = Modifier.testTag("download-read-${entry.id}"), onClick = {
                            importing = importing + entry.id
                            c.action {
                                try {
                                    val ref = importDownloadedDocument(c.store, entry)
                                    c.book(ref)
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
                    IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多下载操作 ${entry.title}") }
                    AppDropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if(entry.status == "已完成") {
                            DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = {
                                more = false
                                val file = File(c.store.downloadsDir, entry.fileName); val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", file)
                                val mime = if(file.extension.lowercase() == "epub") "application/epub+zip" else "text/plain"
                                runCatching { c.app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { c.message("没有找到可打开该文件的应用，可选择开始阅读") }
                            })
                            DropdownMenuItem(text = { Text("导出文件") }, onClick = { more = false; exportId = entry.id; exporter.launch(entry.fileName.substringAfter("${entry.id}-")) })
                            DropdownMenuItem(text = { Text("分享") }, onClick = {
                                more = false
                                val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", File(c.store.downloadsDir, entry.fileName))
                                c.app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            })
                        }
                        DropdownMenuItem(text = { Text("删除") }, onClick = { more = false; remove = entry }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) })
                    }
                }
            }
        } } }
    } }
    remove?.let { entry -> ConfirmDialog("删除下载？", "移除该任务及其下载文件，已导入书架的副本不受影响。", { remove = null }, confirmLabel = "删除下载") { c.action { DownloadWorker.remove(c.app, entry.id) } } }
}
