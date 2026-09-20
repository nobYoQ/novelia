@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

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
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.TextPrompt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.loginForFavorite

@Composable fun FavoriteSheet(c: AppController, book: BookCard, initialCloud: Boolean = false, dismiss: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    var cloud by rememberSaveable(book.ref.key) { mutableStateOf(initialCloud) }
    var create by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    AppSheet(onDismissRequest = dismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text("收藏到书架", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            ChoiceRow("保存位置", listOf("此设备", "原站云端"), if(cloud) 1 else 0) { cloud = it == 1 }
            Text(book.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if(!cloud) {
                state.folders.forEach { folder -> MenuRow(folder, "本地收藏", Icons.Outlined.Folder, { c.store.saveBook(book, folder); c.message("已加入 $folder"); dismiss() }) }
                MenuRow("新建收藏夹", "创建并收藏这本书", Icons.Outlined.CreateNewFolder, { create = true })
            }
            else if(profile == null) EmptyState("登录后继续收藏", "登录成功后会回到这本书的云端收藏选择。", action = "登录后继续", onAction = {
                dismiss()
                loginForFavorite(c, book)
            })
            else AsyncContent(profile?.username, refreshKey = version, load = { c.api.get<CloudFolders>("user/favored") }, modifier = Modifier.heightIn(max = 360.dp)) { data, _ ->
                val folders = if(book.ref.isWenku) data.favoredWenku else data.favoredWeb
                AppLazyColumn { if(folders.isEmpty()) item { Text("创建一个收藏夹，继续收藏这本书。", Modifier.padding(20.dp)) }; items(folders, key = { it.id }) { folder -> MenuRow(folder.title, "与原站同步", Icons.Outlined.CloudQueue, {
                    c.action {
                        val path = if(book.ref.isWenku) "user/favored-wenku/${folder.id}/${book.ref.id}" else "user/favored-web/${folder.id}/${book.ref.key}"
                        val queued = c.cloudMutation("PUT", path)
                        c.store.saveBook(book); dismiss()
                        if(!queued) c.message("已加入云端收藏")
                    }
                }) }; item { MenuRow("新建云端收藏夹", "创建后可在这里选择", Icons.Outlined.CreateNewFolder, { create = true }) } }
            }
        }
    }
    if(create) TextPrompt(if(cloud) "新建云端收藏夹" else "新建收藏夹", "名称", onDismiss = { create = false }) { name ->
        if(cloud) c.action { c.api.post(if(book.ref.isWenku) "user/favored-wenku" else "user/favored-web", mapOf("title" to name)); version++ }
        else { c.store.update { it.copy(folders = (it.folders + name).distinct()) }; c.store.saveBook(book, name); c.message("已加入 $name"); dismiss() }
    }
}
