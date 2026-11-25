@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.book

import cc.novelia.app.ui.components.base.AppTextField
import cc.novelia.app.ui.components.base.AppButton
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.network.updateWebBookText
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.navigation.AppController
import kotlinx.serialization.json.*

@Composable fun EditBookScreen(c: AppController, ref: BookRef) {
    if(ref.isWenku) { WenkuEditorScreen(c, ref.id); return }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    Screen("编辑书籍信息", c::back) { padding ->
        if(profile?.canEdit != true) Box(Modifier.padding(padding)) { EmptyState("当前账号暂不可编辑", "原站通常要求满足账号角色和注册时间条件。", Icons.Outlined.Lock, "查看账号", { c.go("profile") }) }
        else AsyncContent(ref.key, load = { c.api.get<WebDetail>("novel/${ref.key}") }, modifier = Modifier.padding(padding)) { original, _ ->
            var title by remember { mutableStateOf(original.titleZh.orEmpty()) }
            var intro by remember { mutableStateOf(original.introductionZh.orEmpty()) }
            var linked by remember { mutableStateOf(original.wenkuId.orEmpty()) }
            var saving by remember { mutableStateOf(false) }
            AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("修改会同步到原站，请仅提交已核实的信息。", style = MaterialTheme.typography.bodyMedium)
                AppTextField(title, { title = it }, label = { Text("中文标题") }, modifier = Modifier.fillMaxWidth())
                AppTextField(intro, { intro = it }, label = { Text("中文简介") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                AppTextField(linked, { linked = it }, label = { Text("关联文库 ID（可留空）") }, modifier = Modifier.fillMaxWidth())
                AppButton(onClick = { c.action("书籍信息已保存") { saving = true; try {
                    val binding = c.session.capture()
                    val submittedLink = linked.trim()
                    c.api.updateWebBookText(ref, title, intro, binding)
                    if (submittedLink != original.wenkuId.orEmpty()) {
                        val body = buildJsonObject { put("wenkuId", submittedLink) }
                        c.api.request("PUT", "novel/${ref.key}/wenku-id", body.toString(), binding = binding)
                    }
                    c.back()
                } finally { saving = false } } }, enabled = !saving && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if(saving) "保存中…" else "保存到原站") }
            }
        }
    }
}
