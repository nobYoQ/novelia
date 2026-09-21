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
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
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
    var cloud by rememberSaveable(book.ref.key, profile?.username) { mutableStateOf(initialCloud) }
    var create by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    AppSheet(onDismissRequest = dismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text("收藏到书架", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            ChoiceRow("保存位置", listOf("此设备", "原站云端"), if(cloud) 1 else 0) { cloud = it == 1 }
            Text(book.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if(!cloud) {
                val currentFolder = state.books.firstOrNull { it.book.ref == book.ref }?.folder
                state.folders.forEach { folder -> MenuRow(folder, if(folder == currentFolder) "当前本地收藏夹" else "本地收藏", if(folder == currentFolder) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder, { c.store.saveBook(book, folder); c.message("已加入 $folder"); dismiss() }) }
                MenuRow("新建收藏夹", "创建并收藏这本书", Icons.Outlined.CreateNewFolder, { create = true })
            }
            else if(profile == null) EmptyState("登录后继续收藏", "登录成功后会回到这本书的云端收藏选择。", action = "登录后继续", onAction = {
                dismiss()
                loginForFavorite(c, book)
            })
            else AsyncContent(listOf(book.ref, profile?.username), refreshKey = listOf(version, state.syncStatus[profile?.username]?.lastSuccessAt), load = {
                val binding = c.session.capture()
                val folders = c.api.get<CloudFolders>("user/favored")
                // Saved BookCards can belong to an earlier account; read membership from this session's detail cache.
                val favored = if(book.ref.isWenku) c.detail<WenkuDetail>("wenku/${book.ref.id}").favored
                    else c.detail<WebDetail>("novel/${book.ref.key}").favored
                c.session.ensureCurrent(binding)
                folders to favored
            }, modifier = Modifier.heightIn(max = 360.dp)) { (data, favored), _ ->
                val folders = if(book.ref.isWenku) data.favoredWenku else data.favoredWeb
                val favoriteState = bookFavoriteState(book.ref, false, favored, profile?.username, state.pending)
                val path = if(book.ref.isWenku) "user/favored-wenku" else "user/favored-web"
                val bookId = if(book.ref.isWenku) book.ref.id else book.ref.key
                AppLazyColumn {
                    if(folders.isEmpty()) item { Text("创建一个收藏夹，继续收藏这本书。", Modifier.padding(20.dp)) }
                    items(folders, key = { it.id }) { folder ->
                        val selected = folder.id == favoriteState.cloudFolder
                        MenuRow(folder.title, if(selected) "当前云端收藏夹" else "与原站同步", if(selected) Icons.Outlined.BookmarkAdded else Icons.Outlined.CloudQueue, {
                            c.action {
                                val queued = c.cloudMutation("PUT", "$path/${folder.id}/$bookId")
                                dismiss()
                                if(!queued) c.message("已加入云端收藏")
                            }
                        })
                    }
                    favoriteState.cloudFolder?.let { folder -> item {
                        MenuRow("取消云端收藏", "本地书架和阅读记录保留", Icons.Outlined.BookmarkRemove, { c.action {
                            val queued = c.cloudMutation("DELETE", "$path/$folder/$bookId")
                            dismiss()
                            if(!queued) c.message("已取消云端收藏")
                        } })
                    } }
                    item { MenuRow("新建云端收藏夹", "创建后可在这里选择", Icons.Outlined.CreateNewFolder, { create = true }) }
                }
            }
        }
    }
    if(create) TextPrompt(if(cloud) "新建云端收藏夹" else "新建收藏夹", "名称", onDismiss = { create = false }) { name ->
        if(cloud) c.action { c.api.post(if(book.ref.isWenku) "user/favored-wenku" else "user/favored-web", mapOf("title" to name)); version++ }
        else { c.store.update { it.copy(folders = (it.folders + name).distinct()) }; c.store.saveBook(book, name); c.message("已加入 $name"); dismiss() }
    }
}
