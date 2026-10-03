@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.TogglePreference
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.CancellationException

@Composable fun DownloadSheet(c: AppController, book: BookCard, volumes: List<String>, onQueued: () -> Unit = {}, dismiss: () -> Unit) {
    val settings = remember(book.ref) { c.store.state.value.reader }
    var mode by remember { mutableStateOf(if(settings.mode == "jp" && book.ref.isWenku) "zh" else settings.mode) }; var type by remember { mutableStateOf("epub") }; var engine by remember { mutableStateOf(settings.engines.first()) }; var parallel by remember { mutableStateOf(false) }
    val binding = remember(book.ref, volumes) { c.session.capture() }
    var pending by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var queued by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 大字号下表单可能超过一屏；半展开弹层会让已测量滚动视口的一部分
    // 无法被焦点或无障碍滚动请求显露，因此直接完整展开。
    AppSheet(onDismissRequest = { if(!busy) dismiss() }, sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text(if(volumes.size > 1) "批量下载分卷" else "下载小说", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Text(volumes.singleOrNull() ?: book.title, Modifier.padding(horizontal = 20.dp), maxLines = 2)
            if(volumes.size > 1) Text("已选 ${volumes.distinct().size} 卷", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            val modes = if(book.ref.isWenku) listOf("zh", "zh-jp", "jp-zh") else listOf("zh", "jp", "zh-jp", "jp-zh")
            if(pending == null) {
                ChoiceRow("内容", modes.map { mapOf("zh" to "中文", "jp" to "日文", "zh-jp" to "中日", "jp-zh" to "日中").getValue(it) }, modes.indexOf(mode)) { mode = modes[it] }
                if(mode != "jp") { ChoiceRow("首选译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(engine)) { engine = listOf("sakura", "gpt", "youdao")[it] }; TogglePreference("并列保留译文", "关闭时按优先顺序选取已有译文", parallel) { parallel = it } }
                if(!book.ref.isWenku) ChoiceRow("文件格式", listOf("EPUB", "TXT"), if(type == "epub") 0 else 1) { type = if(it == 0) "epub" else "txt" }
            } else Text("已加入 $queued 个下载任务 · 剩余 ${pending.orEmpty().size} 个", Modifier.padding(20.dp))
            Text(if(book.ref.isWenku) "由原站生成已有译文文件，格式沿用原始分卷；下载资格由服务器判断。" else "由原站生成已有内容文件，不会创建新的翻译任务。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            failure?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if(busy) return@Button
                busy = true
                failure = null
                c.action {
                    val previouslyQueued = queued
                    try {
                        c.session.ensureCurrent(binding)
                        if(pending == null) pending = downloadEntries(c.api, book, volumes, mode, engine, parallel, type)
                        while(pending.orEmpty().isNotEmpty()) {
                            c.session.ensureCurrent(binding)
                            DownloadWorker.enqueue(c.app, pending!!.first())
                            pending = pending!!.drop(1)
                            queued++
                        }
                        onQueued()
                        dismiss()
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failure = e.friendlyMessage() }
                    finally { busy = false }
                    val started = queued - previouslyQueued
                    if(started > 0) c.message(if(book.ref.isWenku) "已开始下载 $started 个分卷" else "已开始下载", actionLabel = "查看下载") {
                        c.go("downloads", replaceTop = true)
                    }
                }
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = !busy) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if(busy) "正在加入下载…" else if(pending != null) "重试剩余任务" else "开始下载")
            }
        }
    }
}
