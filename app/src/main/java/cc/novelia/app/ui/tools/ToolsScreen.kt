@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileChoiceRow
import cc.novelia.app.ui.components.CreateBookDocument
import cc.novelia.app.ui.account.ProfileMenuRow
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.readDocument
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable fun ToolsScreen(c: AppController) {
    val libraryState by c.store.state.collectAsStateWithLifecycle()
    var tool by remember { mutableIntStateOf(0) }; var input by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }; var resultBytes by remember { mutableStateOf<ByteArray?>(null) }; var fileName by remember { mutableStateOf("result.txt") }; var busy by remember { mutableStateOf(false) }; var picked by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    val exportFiles = remember(c.store) { PendingExportFiles(c.store.exportsDir) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingExport by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action { val file = withContext(Dispatchers.IO) { readDocument(c, it) }; picked = file; if(tool >= 2) input = withContext(Dispatchers.Default) { DocumentTools.decodeText(file.second) } } } }
    val exporter = rememberLauncherForActivityResult(CreateBookDocument()) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if(id == null) {
            if(uri != null) c.message("待导出结果已不存在，请重新处理文件后再导出")
        } else c.action(if(uri != null) "结果已导出" else null) {
            withContext(Dispatchers.IO) {
                val workContext = coroutineContext
                val destination: (() -> java.io.OutputStream?)? = uri?.let { target -> { c.app.contentResolver.openOutputStream(target, "wt") } }
                exportFiles.finish(id, destination) { workContext.ensureActive() }
            }
        }
    }
    ProfileDetailScreen("文件工具", c::back) { padding -> AppScrollColumn(modifier = Modifier.padding(padding).fillMaxSize(),
        contentModifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileMenuRow("个人术语表", "保存在此设备，可导入与导出 JSON", Icons.Outlined.Translate, { c.go("glossary/local/personal") })
        val previousText = libraryState.drafts["tool:local-ocr"].orEmpty()
        if (previousText.isNotBlank()) TextButton(onClick = {
            tool = 2; input = previousText; output = ""; resultBytes = null; error = null
        }, enabled = !busy, modifier = Modifier.padding(horizontal = 20.dp)) { Text("取回上次校对文本") }
        ProfileChoiceRow("工具", listOf("EPUB 转 TXT", "EPUB 图片压缩", "文本换行整理", "片假名统计"), tool) { if (!busy) { tool = it; output = ""; resultBytes = null; error = null } }
        ProfileDetailCard {
        Text(listOf("按 EPUB 阅读顺序提取正文并导出为文本。", "优化 EPUB 中的图片，保留卷目与原始文件结构。", "合并文本中多余的段内换行，按空行和部分句末、对话标点保留分段。支持选择文本文件或粘贴文本，请检查预览后再导出。", "提取片假名词组并统计频率，辅助整理术语。结果不等同于人名判定。")[tool], Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = !busy) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(picked?.first ?: "选择文件") }
        if(tool >= 2) OutlinedTextField(input, { input = it }, enabled = !busy, label = { Text("粘贴或编辑文本") }, minLines = 7, maxLines = 14, modifier = Modifier.fillMaxWidth().padding(20.dp))
        Button(onClick = {
            val selectedTool = tool; val selectedFile = picked; val selectedInput = input
            busy = true; error = null
            c.action {
                try {
                    val result = withContext(Dispatchers.Default) {
                        when(selectedTool) {
                            0 -> { val file = requireNotNull(selectedFile) { "请先选择 EPUB" }; val text = DocumentTools.epubToTxt(file.second); Triple(text, text.toByteArray(Charsets.UTF_8), file.first.substringBeforeLast('.') + ".txt") }
                            1 -> { val file = requireNotNull(selectedFile) { "请先选择 EPUB" }; val bytes = EpubCompressor.compress(file.second); Triple("原文件：${file.second.size / 1024} KB\n处理后：${bytes.size / 1024} KB", bytes, file.first.substringBeforeLast('.') + ".compressed.epub") }
                            2 -> { val text = DocumentTools.repairOcr(selectedInput) { coroutineContext.ensureActive() }; Triple(text, text.toByteArray(Charsets.UTF_8), "换行整理.txt") }
                            else -> { val text = DocumentTools.katakana(selectedInput).joinToString("\n") { "${it.first}\t${it.second}" }; Triple(text, ("词语\t频次\n$text").toByteArray(Charsets.UTF_8), "片假名统计.tsv") }
                        }
                    }
                    output = result.first; resultBytes = result.second; fileName = result.third
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { error = e.message ?: "处理失败" }
                finally { busy = false }
            }
        }, enabled = !busy && (if(tool < 2) picked != null else input.isNotBlank()), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { Text(if(busy) "处理中…" else "开始处理") }
        if(busy) { if(appReducedMotion()) Text("正在处理…", Modifier.padding(horizontal = 20.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
        error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        }
        if(resultBytes != null) {
            ProfileDetailCard {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("结果预览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !preparingExport && pendingExportId == null, onClick = {
                val bytes = resultBytes
                if(bytes != null && !preparingExport && pendingExportId == null) {
                    val name = fileName
                    preparingExport = true
                    c.action {
                        var stagedId: String? = null
                        var launched = false
                        try {
                            withContext(Dispatchers.IO) {
                                val workContext = coroutineContext
                                stagedId = exportFiles.create(bytes) { workContext.ensureActive() }
                            }
                            pendingExportId = stagedId
                            exporter.launch(name)
                            launched = true
                        } finally {
                            preparingExport = false
                            if(!launched) {
                                pendingExportId = null
                                stagedId?.let { id -> withContext(NonCancellable + Dispatchers.IO) { exportFiles.finish(id, null) } }
                            }
                        }
                    }
                }
                }) { Text(if(preparingExport) "准备导出…" else if(pendingExportId != null) "等待选择位置…" else "导出文件") }
            }
            Text(output.take(12000).ifBlank { "没有找到匹配的内容" } + if(output.length > 12000) "\n…预览已截取，导出包含全部内容。" else "", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } }
}
