@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.downloads

import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppLinearProgressIndicator
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppFilledTonalButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.account.ProfileDetailCard
import android.content.Intent
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import cc.novelia.app.data.library.originBook
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.documents.CreateBookDocument
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.components.documents.ImportResultsPanel
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import cc.novelia.app.files.downloads.DownloadFiles
import cc.novelia.app.files.downloads.deleteBookFiles
import cc.novelia.app.files.exporting.DownloadArchiveFiles
import cc.novelia.app.files.exporting.bookFileMimeType
import cc.novelia.app.files.importing.importDownloadedDocument

@Composable fun DownloadsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var exportId by rememberSaveable { mutableStateOf<String?>(null) }; var remove by remember { mutableStateOf<DownloadEntry?>(null) }
    val reducedMotion = appReducedMotion()
    var importing by remember { mutableStateOf(setOf<String>()) }
    val importer = rememberDownloadImporter(c.app)
    val batch by importer.state.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removeBatch by remember { mutableStateOf<List<String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var permanentDeletion by remember { mutableStateOf<DownloadEntry?>(null) }
    var preparingArchive by remember { mutableStateOf(false) }
    var pendingArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val archives = remember(c.store) { DownloadArchiveFiles(c.store.exportsDir) }
    val operating = deleting || preparingArchive || pendingArchiveId != null || exportId != null
    val available = remember(state.downloads, importing) { state.downloads.filter { it.id !in importing }.map { it.id } }
    val completed = remember(state.downloads, available) { state.downloads.filter { it.status == "已完成" && it.id in available }.map { it.id } }
    val selected = available.filter { it in selection }
    val importable = selected.filter { it in completed }
    LaunchedEffect(available) { selection = selection.filter { it in available } }
    BackHandler(selecting && !operating) { selecting = false; selection = emptyList() }
    val archiveExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingArchiveId
        pendingArchiveId = null
        if(id == null) {
            if(uri != null) c.message("压缩包已不存在，请重新打包")
        } else {
            preparingArchive = true
            c.action(if(uri != null) "压缩包已导出" else null) {
                try {
                    withContext(Dispatchers.IO) {
                        val workContext = coroutineContext
                        archives.finish(id, uri?.let { target -> { c.app.contentResolver.openOutputStream(target, "wt") } }) { workContext.ensureActive() }
                    }
                } finally { preparingArchive = false }
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(CreateBookDocument()) { uri ->
        val pendingId = exportId
        exportId = null
        if(uri != null) {
            preparingArchive = true
            c.action("文件已导出") {
                try {
                    val entry = c.store.state.value.downloads.firstOrNull { it.id == pendingId } ?: error("下载任务已不存在，请重新选择文件")
                    withContext(Dispatchers.IO) {
                        val workContext = coroutineContext
                        DownloadFiles.withTaskLock(c.store.downloadsDir, entry.id) {
                            check(c.store.state.value.downloads.any { it.id == entry.id && it.status == "已完成" && it.fileName == entry.fileName }) { "下载任务已变化，请重新选择文件" }
                            File(c.store.downloadsDir, entry.fileName).inputStream().use { input ->
                                val output = c.app.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入")
                                output.use {
                                    val buffer = ByteArray(65536)
                                    while(true) {
                                        workContext.ensureActive()
                                        val count = input.read(buffer)
                                        if(count < 0) break
                                        it.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    }
                } finally { preparingArchive = false }
            }
        }
    }
    fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newUri(c.app.contentResolver, "下载文件", uri) }
        c.app.startActivity(Intent.createChooser(send, "分享下载文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun transferDownloads(sharing: Boolean) {
        if(operating || batch.running || importable.isEmpty()) return
        val entries = state.downloads.filter { it.id in importable }
        if(entries.size == 1) {
            val entry = entries.single()
            if(sharing) {
                preparingArchive = true
                c.action {
                    try {
                        val file = withContext(Dispatchers.IO) {
                            check(c.store.state.value.downloads.any { it.id == entry.id && it.status == "已完成" && it.fileName == entry.fileName }) { "下载任务已变化，请重新选择文件" }
                            File(c.store.downloadsDir, entry.fileName).also { require(it.isFile) { "下载文件已不存在，请重新下载" } }
                        }
                        shareFile(file, bookFileMimeType(entry.fileName))
                    } finally { preparingArchive = false }
                }
            } else {
                exportId = entry.id
                exporter.launch(entry.fileName.removePrefix("${entry.id}-"))
            }
            return
        }
        preparingArchive = true
        c.action {
            var stagedId: String? = null
            var sharedFile: File? = null
            var launched = false
            try {
                val id = archives.create(c.store.downloadsDir, entries) { entry ->
                    c.store.state.value.downloads.any { it.id == entry.id && it.fileName == entry.fileName && it.status == "已完成" }
                }
                stagedId = id
                if(sharing) {
                    val file = withContext(Dispatchers.IO) { archives.share(id) }
                    sharedFile = file
                    shareFile(file, "application/zip")
                } else {
                    pendingArchiveId = id
                    archiveExporter.launch("下载文件（${entries.size}）.zip")
                }
                launched = true
            } finally {
                preparingArchive = false
                if(!launched) {
                    pendingArchiveId = null
                    withContext(NonCancellable + Dispatchers.IO) {
                        stagedId?.let { archives.finish(it, null) }
                        sharedFile?.delete()
                    }
                }
            }
        }
    }
    ProfileDetailScreen("下载管理", c::back, actions = {
        AppTextButton(onClick = { selecting = !selecting; selection = emptyList() },
            enabled = !operating && !batch.running && importing.isEmpty() && (selecting || available.isNotEmpty())) {
            Icon(if(selecting) Icons.Outlined.Close else Icons.Outlined.Checklist, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if(selecting) "取消" else "批量操作")
        }
    }) { padding -> Column(Modifier.padding(padding)) {
        if(selecting) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已选 ${selected.size} 个", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
            val allSelected = available.isNotEmpty() && selected.size == available.size
            AppTextButton(onClick = { selection = if(allSelected) emptyList() else available }, enabled = !operating && available.isNotEmpty()) {
                Text(if(allSelected) "取消全选" else "全选")
            }
            AppTextButton(onClick = { selection = completed }, enabled = !operating && completed.isNotEmpty()) {
                Text("全选已完成")
            }
            AppFilledTonalButton(onClick = {
                importer.start(state.downloads.filter { it.id in importable })
                selecting = false
                selection = emptyList()
            }, enabled = !operating && !batch.running && importing.isEmpty() && importable.isNotEmpty(), modifier = Modifier.testTag("download-batch-import")) {
                Icon(Icons.Outlined.LibraryAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入书架（${importable.size}）")
            }
            AppOutlinedButton(onClick = { transferDownloads(false) }, enabled = !operating && !batch.running && importing.isEmpty() && importable.isNotEmpty(),
                modifier = Modifier.testTag("download-batch-export")) {
                Icon(if(importable.size > 1) Icons.Outlined.FolderZip else Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text("${if(importable.size > 1) "导出 ZIP" else "导出文件"}（${importable.size}）")
            }
            AppOutlinedButton(onClick = { transferDownloads(true) }, enabled = !operating && !batch.running && importing.isEmpty() && importable.isNotEmpty(),
                modifier = Modifier.testTag("download-batch-share")) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text("${if(importable.size > 1) "分享 ZIP" else "分享文件"}（${importable.size}）")
            }
            AppOutlinedButton(onClick = { removeBatch = selected }, enabled = !operating && !batch.running && importing.isEmpty() && selected.isNotEmpty(),
                modifier = Modifier.testTag("download-batch-delete")) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if(deleting) "正在删除…" else "删除（${selected.size}）")
            }
        }
        if(selecting) Text(when { preparingArchive -> "正在处理文件…"; pendingArchiveId != null || exportId != null -> "等待选择导出位置…";
            else -> "导入、导出和分享仅处理已完成文件；删除可处理所有选中任务。" }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImportResultsPanel(batch.items, batch.running, importer::pause, { if(!operating) importer.retryFailed() }, { if(!operating) importer.resume() }, c::book)
        ProfileDetailList(Modifier.weight(1f).testTag("downloads-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(state.downloads.isEmpty()) item { ProfileEmptyState("还没有下载任务", "在作品详情或文库分卷中下载小说，完成后可以导出或导入阅读。", Icons.Outlined.Download) }
        items(state.downloads, key = { it.id }, contentType = { "download" }) { entry ->
            var more by remember(entry.id) { mutableStateOf(false) }
            val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
            val selectionModifier = if(selecting) Modifier.testTag("download-select-${entry.id}")
                .toggleable(entry.id in selected, enabled = !operating && entry.id in available, role = Role.Checkbox) { checked ->
                    selection = if(checked) selection + entry.id else selection - entry.id
                } else Modifier
            ProfileDetailCard(itemMotion.fillMaxWidth().animateContentSize(tween(if(reducedMotion) 0 else AppMotion.Standard)).then(selectionModifier)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if(selecting) AppCheckbox(entry.id in selected, onCheckedChange = null, enabled = !operating && entry.id in available)
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
                AppLinearProgressIndicator(progress = { if(reducedMotion) (entry.progress / 100f).coerceIn(0f, 1f) else progress.value }, modifier = Modifier.fillMaxWidth())
            }
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if(entry.status == "已暂停") Text("重新开始会从头下载此文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(!selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when(entry.status) {
                    "下载中", "等待下载" -> AppTextButton(onClick = { c.action { DownloadWorker.pause(c.app, entry.id) } }) { Text("暂停") }
                    "已完成" -> {
                        AppButton(enabled = !operating && !batch.running && entry.id !in importing, modifier = Modifier.testTag("download-read-${entry.id}"), onClick = {
                            importing = importing + entry.id
                            c.action {
                                try {
                                    c.book(importDownloadedDocument(c.app, entry).ref)
                                } finally { importing = importing - entry.id }
                            }
                        }) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(entry.id in importing) "正在准备…" else "开始阅读") }
                    }
                    else -> AppFilledTonalButton(onClick = {
                        val restart = { c.action { c.store.state.value.downloads.firstOrNull { it.id == entry.id }?.let { DownloadWorker.enqueue(c.app, it) } } }
                        if(entry.status == "需要登录") { c.afterLogin = restart; c.go("login") } else restart()
                    }) { Text(downloadRecoveryLabel(entry.status)) }
                }
                Box {
                    AppIconButton(onClick = { more = true }, enabled = !operating && !batch.running && entry.id !in importing) { Icon(Icons.Outlined.MoreVert, "更多下载操作 ${entry.title}") }
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
                        DropdownMenuItem(text = { Text(if(entry.originBook()?.isWenku == true) "彻底删除此分卷" else "彻底删除小说") },
                            onClick = { more = false; permanentDeletion = entry }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) })
                    }
                }
            }
        } } }
    } } }
    remove?.let { entry -> ConfirmDialog("删除下载？", "移除该任务及其下载文件，已导入书架的副本不受影响。", { remove = null }, confirmLabel = "删除下载") { c.action { DownloadWorker.remove(c.app, entry.id) } } }
    permanentDeletion?.let { entry ->
        ConfirmDialog(if(entry.originBook()?.isWenku == true) "彻底删除此分卷？" else "彻底删除小说？",
            "同时删除对应书架条目、导入副本、下载任务和文件，以及本机阅读记录和笔记。文库的其他分卷保留。导入前的原文件和原站收藏不受影响，此操作无法撤销。",
            { permanentDeletion = null }, confirmLabel = "彻底删除") {
            deleting = true
            c.action("小说及关联文件已彻底删除") {
                try {
                    val source = entry.originBook()?.takeUnless { it.isWenku }
                    deleteBookFiles(c.app, refs = setOfNotNull(source), downloadIds = setOf(entry.id))
                } finally { deleting = false }
            }
        }
    }
    removeBatch?.let { ids -> ConfirmDialog("删除 ${ids.size} 个下载？", "移除选中任务及其下载文件，正在下载的任务会取消，已导入书架的副本不受影响。",
        { removeBatch = null }, confirmLabel = "删除下载") {
        c.action("已删除 ${ids.size} 个下载") {
            deleting = true
            try {
                ids.forEach { DownloadWorker.remove(c.app, it) }
                selecting = false
                selection = emptyList()
            } finally { deleting = false }
        }
    } }
}
