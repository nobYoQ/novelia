@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController
import kotlinx.serialization.json.*

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
