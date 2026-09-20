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
import cc.novelia.app.ui.navigation.AppController
import java.util.UUID

@Composable fun DownloadSheet(c: AppController, book: BookCard, volume: String?, dismiss: () -> Unit) {
    val settings = remember(book.ref) { c.store.state.value.reader }
    var mode by remember { mutableStateOf(if(settings.mode == "jp" && book.ref.isWenku) "zh" else settings.mode) }; var type by remember { mutableStateOf("epub") }; var engine by remember { mutableStateOf(settings.engines.first()) }; var parallel by remember { mutableStateOf(false) }
    // This form can exceed one screen at large font sizes. A half-expanded sheet would
    // hide part of its measured scroll viewport from focus/accessibility scroll requests.
    AppSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
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
                c.action { DownloadWorker.enqueue(c.app, DownloadEntry(id, volume ?: book.title, "$id-$filename", url, sourceBook = book.ref)); dismiss(); c.message("已加入下载列表"); c.go("downloads") }
            }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始下载") }
        }
    }
}
