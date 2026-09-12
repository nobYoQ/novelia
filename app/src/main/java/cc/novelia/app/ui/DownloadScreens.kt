@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.files.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable fun DownloadSheet(c: AppController, book: BookCard, volume: String?, dismiss: () -> Unit) {
    val settings = remember(book.ref) { c.store.state.value.reader }
    var mode by remember { mutableStateOf(if(settings.mode == "jp" && book.ref.isWenku) "zh" else settings.mode) }; var type by remember { mutableStateOf("epub") }; var engine by remember { mutableStateOf(settings.engines.first()) }; var parallel by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("下载小说", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Text(volume ?: book.title, Modifier.padding(horizontal = 20.dp), maxLines = 2)
            val modes = if(book.ref.isWenku) listOf("zh", "zh-jp", "jp-zh") else listOf("zh", "jp", "zh-jp", "jp-zh")
            ChoiceRow("内容", modes.map { mapOf("zh" to "中文", "jp" to "日文", "zh-jp" to "中日", "jp-zh" to "日中").getValue(it) }, modes.indexOf(mode)) { mode = modes[it] }
            if(mode != "jp") { ChoiceRow("首选译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(engine)) { engine = listOf("sakura", "gpt", "youdao")[it] }; TogglePreference("并列保留译文", "关闭时按优先顺序选取已有译文", parallel) { parallel = it } }
            if(!book.ref.isWenku) ChoiceRow("文件格式", listOf("EPUB", "TXT"), if(type == "epub") 0 else 1) { type = if(it == 0) "epub" else "txt" }
            Text(if(book.ref.isWenku) "由原站生成已有译文文件，格式沿用原始分卷；下载资格由服务器判断。" else "由原站生成已有内容文件，不会创建新的翻译任务。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val id = UUID.randomUUID().toString(); val name = (volume ?: "${book.title}.$type").replace(Regex("[\\/\\\\:*?\"<>|]"), "_").takeLast(150)
                val filename = "$mode.$name"
                val engines = listOf(engine) + listOf("sakura", "gpt", "youdao").filterNot { it == engine }
                val url = c.api.downloadUrl(book.ref, volume, mode, engines, parallel, type, filename)
                c.action { DownloadWorker.enqueue(c.app, DownloadEntry(id, volume ?: book.title, "$id-$filename", url)); dismiss(); c.message("已加入下载列表"); c.go("downloads") }
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始下载") }
        }
    }
}
@Composable fun DownloadsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var export by remember { mutableStateOf<DownloadEntry?>(null) }; var remove by remember { mutableStateOf<DownloadEntry?>(null) }
    val reducedMotion = LocalReducedMotion.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val entry = export; if(uri != null && entry != null) c.action("文件已导出") { withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(uri)?.use { output -> File(c.store.downloadsDir, entry.fileName).inputStream().use { it.copyTo(output) } } ?: error("无法写入") } }; export = null }
    Screen("下载管理", c::back) { padding -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(state.downloads.isEmpty()) item { EmptyState("还没有下载任务", "在作品详情或文库分卷中下载小说，完成后可以导出或导入阅读。", Icons.Outlined.Download) }
        items(state.downloads, key = { it.id }, contentType = { "download" }) { entry ->
            val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(120))
            Card(itemMotion.fillMaxWidth().animateContentSize(tween(if(reducedMotion) 0 else 220))) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                val progress = animateFloatAsState((entry.progress / 100f).coerceIn(0f, 1f), tween(if(reducedMotion) 0 else 220, easing = LinearEasing), label = "download progress")
                LinearProgressIndicator(progress = { if(reducedMotion) (entry.progress / 100f).coerceIn(0f, 1f) else progress.value }, modifier = Modifier.fillMaxWidth())
            }
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when(entry.status) {
                    "下载中", "等待下载" -> TextButton(onClick = { c.action { DownloadWorker.pause(c.app, entry.id) } }) { Text("暂停") }
                    "已完成" -> {
                        TextButton(onClick = { val file = File(c.store.downloadsDir, entry.fileName); val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", file); val mime = if(file.extension.lowercase() == "epub") "application/epub+zip" else "text/plain"; runCatching { c.app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { c.message("没有找到可打开该文件的应用，可选择导入阅读") } }) { Text("打开") }
                        TextButton(onClick = { c.action("已导入书架", MidoriSticker.Approve) { withContext(Dispatchers.IO) { val doc = DocumentTools.parse(entry.fileName, File(c.store.downloadsDir, entry.fileName).readBytes()).copy(name = entry.title); c.store.saveDocument(doc); c.store.saveBook(BookCard(BookRef("local", doc.id), doc.name, cover = doc.coverImage?.let { c.store.documentImage(doc.id, it).absolutePath }, subtitle = "离线文件 · ${doc.chapters.size} 章")) } } }) { Text("导入阅读") }
                        TextButton(onClick = { export = entry; exporter.launch(entry.fileName.substringAfter("${entry.id}-")) }) { Text("导出文件") }
                        TextButton(onClick = { val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", File(c.store.downloadsDir, entry.fileName)); c.app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("分享") }
                    }
                    else -> TextButton(onClick = { c.action { DownloadWorker.enqueue(c.app, entry) } }) { Text("重新下载") }
                }
                TextButton(onClick = { remove = entry }) { Text("删除") }
            }
        } } }
    } }
    remove?.let { entry -> ConfirmDialog("删除下载？", "移除该任务及其下载文件，已导入书架的副本不受影响。", { remove = null }) { c.action { DownloadWorker.pause(c.app, entry.id); withContext(Dispatchers.IO) { File(c.store.downloadsDir, entry.fileName).delete(); File(c.store.downloadsDir, "${entry.id}.part").delete() }; c.store.update { it.copy(downloads = it.downloads.filterNot { d -> d.id == entry.id }) }; c.store.flush() } } }
}
