@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.files.PendingExportFiles
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.readDocument
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

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
