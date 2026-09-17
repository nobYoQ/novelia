@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneOffset

@Composable fun WenkuEditorScreen(c: AppController, id: String? = null) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    if(profile?.canEdit != true) { Screen("文库条目维护", c::back) { padding -> Box(Modifier.padding(padding)) { EmptyState("需要编辑权限", "登录满足原站角色与注册时间条件的账号后可维护文库条目。", Icons.Outlined.Lock, "查看账号", { c.go("profile") }) } }; return }
    if(id == null) WenkuForm(c, null, WenkuDetail())
    else AsyncContent(id, { c.api.get<WenkuDetail>("wenku/$id") }) { detail, _ -> WenkuForm(c, id, detail) }
}
@Composable private fun WenkuForm(c: AppController, id: String?, original: WenkuDetail) {
    val draftKey = "wenku:${id ?: "new"}"
    val draft = remember(id) { c.store.state.value.drafts[draftKey]?.let { runCatching { appJson.decodeFromString<WenkuDetail>(it) }.getOrNull() } ?: original }
    var title by rememberSaveable(id) { mutableStateOf(draft.title) }; var titleZh by rememberSaveable(id) { mutableStateOf(draft.titleZh) }; var authors by rememberSaveable(id) { mutableStateOf(draft.authors.joinToString("\n")) }; var artists by rememberSaveable(id) { mutableStateOf(draft.artists.joinToString("\n")) }; var intro by rememberSaveable(id) { mutableStateOf(draft.introduction) }; var cover by rememberSaveable(id) { mutableStateOf(draft.cover.orEmpty()) }; var tags by rememberSaveable(id) { mutableStateOf(draft.keywords.joinToString("\n")) }; var level by rememberSaveable(id) { mutableStateOf(draft.level) }; var volumes by remember { mutableStateOf(draft.volumes) }; var volumeEditor by remember { mutableStateOf<Int?>(null) }; var saving by remember { mutableStateOf(false) }; var duplicate by remember { mutableStateOf<List<WenkuOutline>?>(null) }; var ignoreDuplicate by remember { mutableStateOf(false) }
    fun values() = WenkuDetail(title = title.trim(), titleZh = titleZh.trim(), cover = cover.trim().ifBlank { null }, authors = authors.lines().map(String::trim).filter(String::isNotBlank), artists = artists.lines().map(String::trim).filter(String::isNotBlank), keywords = tags.lines().map(String::trim).filter(String::isNotBlank), level = level, introduction = intro.trim(), volumes = volumes)
    LaunchedEffect(title, titleZh, authors, artists, intro, cover, tags, level, volumes) { kotlinx.coroutines.delay(700); c.store.update { it.copy(drafts = it.drafts + (draftKey to appJson.encodeToString(values()))) } }
    fun submit() { c.action { saving = true; try {
        if(id == null && !ignoreDuplicate) {
            val found = c.api.wenkuList(0, title.trim()).items
            if(found.isNotEmpty()) { duplicate = found; return@action }
        }
        val data = appJson.encodeToJsonElement(values()).jsonObject.filterKeys { it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes") }
        if(id != null) {
            val latest = c.api.get<WenkuDetail>("wenku/$id")
            if(latest.title != original.title || latest.titleZh != original.titleZh || latest.introduction != original.introduction || latest.volumes != original.volumes) throw ApiException(409, "条目已被更新，请重新进入后核对再保存")
        }
        val result = if(id == null) c.api.post("wenku", JsonObject(data)) else c.api.put("wenku/$id", JsonObject(data))
        c.store.update { it.copy(drafts = it.drafts - draftKey) }; c.back(); c.book(BookRef("wenku", id ?: result.trim().trim('"'))); c.message("文库条目已保存")
    } finally { saving = false } } }
    Screen(if(id == null) "新建文库条目" else "编辑文库条目", c::back) { padding -> AppScrollColumn(modifier = Modifier.padding(padding), contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("草稿自动保存在此设备。保存会更新原站资料。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, { title = it }, label = { Text("原文标题") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(titleZh, { titleZh = it }, label = { Text("中文标题") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(authors, { authors = it }, label = { Text("作者，每行一位") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(artists, { artists = it }, label = { Text("插画师，每行一位") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cover, { cover = it }, label = { Text("封面 HTTPS 链接") }, modifier = Modifier.fillMaxWidth())
        val levels = listOf("一般向", "轻文学", "严肃向", "非小说", "成人向", "成人向女")
        ChoiceRow("分类", levels, levels.indexOf(level)) { level = levels[it] }
        OutlinedTextField(tags, { tags = it }, label = { Text("标签，每行一个") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(intro, { intro = it }, label = { Text("简介") }, minLines = 6, modifier = Modifier.fillMaxWidth())
        SectionTitle("出版分卷 ${volumes.size}", "添加分卷") { volumeEditor = -1 }
        volumes.forEachIndexed { index, volume -> ListItem(headlineContent = { Text(volume.titleZh ?: volume.title) }, supportingContent = { Text(volume.asin) }, trailingContent = { Row { IconButton(onClick = { volumeEditor = index }) { Icon(Icons.Outlined.Edit, "编辑分卷") }; IconButton(onClick = { volumes = volumes.filterIndexed { i, _ -> i != index } }) { Icon(Icons.Outlined.DeleteOutline, "移除分卷信息") } } }) }
        Text("日亚资料导入依赖原站扩展。本页支持手动填写已核实的出版资料。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = ::submit, enabled = !saving && title.isNotBlank() && titleZh.isNotBlank() && (cover.isBlank() || cover.startsWith("https://")), modifier = Modifier.fillMaxWidth()) { Text(if(saving) "保存中…" else "保存到原站") }
    } }
    volumeEditor?.let { index ->
        val existing = volumes.getOrNull(index) ?: WenkuVolume()
        var asin by remember(index) { mutableStateOf(existing.asin) }; var name by remember(index) { mutableStateOf(existing.title) }; var translated by remember(index) { mutableStateOf(existing.titleZh.orEmpty()) }; var image by remember(index) { mutableStateOf(existing.cover.orEmpty()) }; var publisher by remember(index) { mutableStateOf(existing.publisher.orEmpty()) }; var imprint by remember(index) { mutableStateOf(existing.imprint.orEmpty()) }; var date by remember(index) { mutableStateOf(existing.publishAt?.let { java.time.Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()) }
        AlertDialog(onDismissRequest = { volumeEditor = null }, title = { Text("出版分卷") }, text = { AppScrollColumn(contentModifier = Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(asin, { asin = it.trim() }, label = { Text("ASIN") }); OutlinedTextField(name, { name = it }, label = { Text("原文卷名") }); OutlinedTextField(translated, { translated = it }, label = { Text("中文卷名") }); OutlinedTextField(image, { image = it }, label = { Text("封面 HTTPS 链接") }); OutlinedTextField(publisher, { publisher = it }, label = { Text("出版社") }); OutlinedTextField(imprint, { imprint = it }, label = { Text("文库品牌") }); OutlinedTextField(date, { date = it }, label = { Text("出版日期 YYYY-MM-DD") })
        } }, confirmButton = { TextButton(onClick = { val timestamp = date.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }.getOrNull() }; if(date.isNotBlank() && timestamp == null) { c.message("请填写有效的出版日期") } else { val volume = existing.copy(asin = asin, title = name, titleZh = translated.ifBlank { null }, cover = image.ifBlank { null }, publisher = publisher.ifBlank { null }, imprint = imprint.ifBlank { null }, publishAt = timestamp); volumes = if(index < 0) volumes + volume else volumes.toMutableList().apply { set(index, volume) }; volumeEditor = null } }, enabled = asin.isNotBlank() && name.isNotBlank() && (image.isBlank() || image.startsWith("https://"))) { Text("保存分卷") } }, dismissButton = { TextButton(onClick = { volumeEditor = null }) { Text("取消") } })
    }
    duplicate?.let { matches ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("发现可能重复的条目") },
            text = {
                AppScrollColumn(contentModifier = Modifier) {
                    Text("请先确认以下作品不是你要创建的文库。")
                    matches.forEach { book ->
                        TextButton(onClick = { duplicate = null; c.book(BookRef("wenku", book.id)) }) {
                            Text(book.titleZh.ifBlank { book.title })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { duplicate = null; ignoreDuplicate = true; submit() }) {
                    Text("确认是新作品，继续创建")
                }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("返回检查") } }
        )
    }
}
