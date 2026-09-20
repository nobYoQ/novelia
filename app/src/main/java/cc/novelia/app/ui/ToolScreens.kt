@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.files.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Composable fun ToolsScreen(c: AppController) {
    val libraryState by c.store.state.collectAsStateWithLifecycle()
    var tool by remember { mutableIntStateOf(0) }; var input by remember { mutableStateOf("") }; var output by remember { mutableStateOf("") }; var resultBytes by remember { mutableStateOf<ByteArray?>(null) }; var fileName by remember { mutableStateOf("result.txt") }; var busy by remember { mutableStateOf(false) }; var picked by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    val exportFiles = remember(c.store) { PendingExportFiles(c.store.exportsDir) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingExport by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action { val file = withContext(Dispatchers.IO) { readDocument(c, it) }; picked = file; if(tool >= 2) input = withContext(Dispatchers.Default) { DocumentTools.decodeText(file.second) } } } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if(id == null) {
            if(uri != null) c.message("待导出结果已不存在，请重新处理文件后再导出")
        } else c.action(if(uri != null) "结果已导出" else null) {
            withContext(Dispatchers.IO) {
                val workContext = coroutineContext
                val destination: (() -> java.io.OutputStream?)? = uri?.let { target -> { c.app.contentResolver.openOutputStream(target) } }
                exportFiles.finish(id, destination) { workContext.ensureActive() }
            }
        }
    }
    Screen("文件工具", c::back) { padding -> AppScrollColumn(modifier = Modifier.padding(padding), contentModifier = Modifier.padding(bottom = 24.dp)) {
        MenuRow("个人术语表", "保存在此设备，可导入与导出 JSON", Icons.Outlined.Translate, { c.go("glossary/local/personal") })
        val previousText = libraryState.drafts["tool:local-ocr"].orEmpty()
        if (previousText.isNotBlank()) TextButton(onClick = {
            tool = 2; input = previousText; output = ""; resultBytes = null; error = null
        }, enabled = !busy, modifier = Modifier.padding(horizontal = 20.dp)) { Text("取回上次校对文本") }
        ChoiceRow("工具", listOf("EPUB 转 TXT", "EPUB 图片压缩", "文本换行整理", "片假名统计"), tool) { if (!busy) { tool = it; output = ""; resultBytes = null; error = null } }
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
        if(resultBytes != null) {
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
    } }
}

@Composable fun GlossaryScreen(c: AppController, ref: BookRef) {
    var data by remember { mutableStateOf<Map<String, String>?>(null) }; var original by remember { mutableStateOf<Map<String, String>>(emptyMap()) }; var query by remember { mutableStateOf("") }; var editing by remember { mutableStateOf<Pair<String, String>?>(null) }; var add by remember { mutableStateOf(false) }; var saving by remember { mutableStateOf(false) }
    val exportFiles = remember(c.store) { PendingExportFiles(c.store.exportsDir) }
    var pendingExportId by rememberSaveable(ref.key) { mutableStateOf<String?>(null) }
    var preparingExport by remember(ref.key) { mutableStateOf(false) }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val canEdit = ref.isLocal || profile?.canEdit == true
    val settledQuery = rememberDebouncedQuery(query)
    val entries = remember(data, settledQuery) { data.orEmpty().entries.filter { it.key.contains(settledQuery, true) || it.value.contains(settledQuery, true) } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action("术语表已载入，请检查后保存") { val (_, bytes) = withContext(Dispatchers.IO) { readDocument(c, it) }; val parsed = appJson.decodeFromString<Map<String, String>>(bytes.toString(Charsets.UTF_8)); require(parsed.size <= 5000 && parsed.keys.none(String::isBlank)); data = data.orEmpty() + parsed } } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if(id == null) {
            if(uri != null) c.message("待导出术语表已不存在，请重新选择导出")
        } else c.action(if(uri != null) "术语表已导出" else null) {
            withContext(Dispatchers.IO) {
                val workContext = coroutineContext
                val destination: (() -> java.io.OutputStream?)? = uri?.let { target -> { c.app.contentResolver.openOutputStream(target) } }
                exportFiles.finish(id, destination) { workContext.ensureActive() }
            }
        }
    }
    Screen("术语表", c::back, actions = {
        IconButton(onClick = {
            val submittedData = data?.toMap()
            if(submittedData != null && !preparingExport && pendingExportId == null) {
                preparingExport = true
                c.action {
                    var stagedId: String? = null
                    var launched = false
                    try {
                        withContext(Dispatchers.IO) {
                            val workContext = coroutineContext
                            val bytes = appJson.encodeToString(submittedData).toByteArray(Charsets.UTF_8)
                            stagedId = exportFiles.create(bytes) { workContext.ensureActive() }
                        }
                        pendingExportId = stagedId
                        exporter.launch("${ref.id}.glossary.json")
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
        }, enabled = data != null && !preparingExport && pendingExportId == null) {
            Icon(Icons.Outlined.IosShare, if(preparingExport) "正在准备导出" else if(pendingExportId != null) "等待选择导出位置" else "导出 JSON")
        }
        if(canEdit) { IconButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Outlined.FileOpen, "导入 JSON") }; IconButton(onClick = { add = true }) { Icon(Icons.Outlined.Add, "添加词条") } }
    }) { padding -> AsyncContent(ref.key, load = { if(ref.isLocal) c.store.state.value.personalGlossaries[ref.key].orEmpty() else if(ref.isWenku) c.api.get<WenkuDetail>("wenku/${ref.id}").glossary else c.api.get<WebDetail>("novel/${ref.key}").glossary }, modifier = Modifier.padding(padding)) { glossary, _ ->
        LaunchedEffect(glossary) { if(data == null) { data = glossary; original = glossary } }
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("查找原文或译名") }, modifier = Modifier.fillMaxWidth().padding(20.dp), singleLine = true)
            AppLazyColumn(Modifier.weight(1f)) {
                if(entries.isEmpty()) item { EmptyState("暂时没有匹配词条", "术语表用于统一作品中的人名和专有名词。", Icons.Outlined.Translate) }
                items(entries, key = { it.key }) { item -> ListItem(headlineContent = { Text(item.key) }, supportingContent = { Text(item.value) }, trailingContent = { if(canEdit) Row { IconButton(onClick = { editing = item.key to item.value }) { Icon(Icons.Outlined.Edit, "编辑词条") }; IconButton(onClick = { data = data.orEmpty() - item.key }) { Icon(Icons.Outlined.DeleteOutline, "删除词条") } } }) }
            }
            if(canEdit) Button(onClick = { c.action("术语表已保存") { saving = true; val submittedData = data.orEmpty().toMap(); val submittedOriginal = original; try { if(ref.isLocal) c.store.update { it.copy(personalGlossaries = it.personalGlossaries + (ref.key to submittedData)) } else { val latest = if(ref.isWenku) c.api.get<WenkuDetail>("wenku/${ref.id}").glossary else c.api.get<WebDetail>("novel/${ref.key}").glossary; if(latest != submittedOriginal) throw ApiException(409, "原站术语表已被他人更新，请重新进入此页后再编辑"); c.api.put(if(ref.isWenku) "wenku/${ref.id}/glossary" else "novel/${ref.key}/glossary", submittedData) }; original = submittedData } finally { saving = false } } }, enabled = data != null && data != original && !saving, modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(if(saving) "保存中…" else if(ref.isLocal) "保存到此设备" else "保存到原站") }
            else Text("维护术语表需要符合原站编辑权限；当前可浏览和导出。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        }
    } }
    if(add || editing != null) {
        var source by remember(editing) { mutableStateOf(editing?.first.orEmpty()) }; var target by remember(editing) { mutableStateOf(editing?.second.orEmpty()) }
        AppAlertDialog(onDismissRequest = { add = false; editing = null }, title = { Text(if(add) "添加词条" else "编辑词条") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(source, { source = it }, label = { Text("原文") }); OutlinedTextField(target, { target = it }, label = { Text("译名") }) } }, confirmButton = { TextButton(onClick = { data = (data.orEmpty() - (editing?.first ?: "")) + (source.trim() to target.trim()); add = false; editing = null }, enabled = source.isNotBlank() && target.isNotBlank()) { Text("保存词条") } }, dismissButton = { TextButton(onClick = { add = false; editing = null }) { Text("取消") } })
    }
}

@Composable fun EditBookScreen(c: AppController, ref: BookRef) {
    if(ref.isWenku) { WenkuEditorScreen(c, ref.id); return }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    Screen("编辑书籍信息", c::back) { padding ->
        if(profile?.canEdit != true) Box(Modifier.padding(padding)) { EmptyState("当前账号暂不可编辑", "原站通常要求满足账号角色和注册时间条件。", Icons.Outlined.Lock, "查看账号", { c.go("profile") }) }
        else AsyncContent(ref.key, load = { appJson.parseToJsonElement(c.api.request("GET", if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}")).jsonObject }, modifier = Modifier.padding(padding)) { original, _ ->
            fun field(key: String) = original[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            var title by remember { mutableStateOf(field(if(ref.isWenku) "titleZh" else "titleZh")) }; var jp by remember { mutableStateOf(field(if(ref.isWenku) "title" else "titleJp")) }; var intro by remember { mutableStateOf(field(if(ref.isWenku) "introduction" else "introductionZh")) }; var linked by remember { mutableStateOf(field("wenkuId")) }; var authors by remember { mutableStateOf(original["authors"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("\n").orEmpty()) }; var tags by remember { mutableStateOf(original["keywords"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }; var saving by remember { mutableStateOf(false) }
            AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("修改会同步到原站，请仅提交已核实的信息。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(title, { title = it }, label = { Text("中文标题") }, modifier = Modifier.fillMaxWidth())
                if(ref.isWenku) { OutlinedTextField(jp, { jp = it }, label = { Text("原文标题") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(authors, { authors = it }, label = { Text("作者，每行一位") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(tags, { tags = it }, label = { Text("标签，每行一个") }, modifier = Modifier.fillMaxWidth()) }
                OutlinedTextField(intro, { intro = it }, label = { Text("中文简介") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                if(!ref.isWenku) OutlinedTextField(linked, { linked = it }, label = { Text("关联文库 ID（可留空）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { c.action("书籍信息已保存") { saving = true; try {
                    if(ref.isWenku) {
                        val body = original.filterKeys { it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes") }.toMutableMap()
                        body["title"] = JsonPrimitive(jp); body["titleZh"] = JsonPrimitive(title); body["introduction"] = JsonPrimitive(intro); body["authors"] = JsonArray(authors.lines().filter(String::isNotBlank).map(::JsonPrimitive)); body["keywords"] = JsonArray(tags.lines().filter(String::isNotBlank).map(::JsonPrimitive))
                        c.api.put("wenku/${ref.id}", JsonObject(body))
                    } else {
                        val toc = original["toc"]?.jsonArray.orEmpty().mapNotNull { e -> val item = e.jsonObject; val t = item["titleZh"]?.jsonPrimitive?.contentOrNull; if(t != null) item.getValue("titleJp").jsonPrimitive.content to JsonPrimitive(t) else null }.toMap()
                        c.api.put("novel/${ref.key}/translation", buildJsonObject { put("title", title); put("introduction", intro); put("toc", JsonObject(toc)) })
                        if(linked != field("wenkuId")) c.api.put("novel/${ref.key}/wenku-id", mapOf("wenkuId" to linked))
                    }
                    c.back()
                } finally { saving = false } } }, enabled = !saving && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if(saving) "保存中…" else "保存到原站") }
            }
        }
    }
}
