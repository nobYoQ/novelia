@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.CLOUD_DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.cloudFavoriteFolderOrder
import cc.novelia.app.data.model.orderedLocalFavoriteFolders
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.components.base.TextPrompt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.addLocalFavorite
import cc.novelia.app.ui.navigation.loginForFavorite

@Composable fun FavoriteSheet(c: AppController, book: BookCard, initialCloud: Boolean = false, dismiss: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val account = profile?.username
    var cloud by rememberSaveable(book.ref.key, account, initialCloud) { mutableStateOf(initialCloud) }
    var create by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    AppSheet(onDismissRequest = dismiss) {
        // 面板按可用高度约束，只有下方内容滚动，避免切换或加载结果改变展开位置。
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).fillMaxHeight().testTag("favorite-choice-panel")) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("收藏到书架", style = MaterialTheme.typography.titleLarge)
                Text(book.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PrimaryTabRow(if(cloud) 1 else 0, containerColor = Color.Transparent) {
                Tab(!cloud, onClick = { cloud = false }, text = { Text("此设备") })
                Tab(cloud, onClick = { cloud = true }, text = { Text("原站云端") })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !cloud -> LocalFavoriteOptions(c, book, state, dismiss) { create = true }
                    account != null -> CloudFavoriteOptions(c, book, state, account, version, dismiss) { create = true }
                    else -> AppScrollColumn(Modifier.fillMaxSize()) {
                        EmptyState("登录后继续收藏", "登录成功后会回到这本书的云端收藏选择。", action = "登录后继续", onAction = {
                            dismiss()
                            loginForFavorite(c, book)
                        })
                    }
                }
            }
        }
    }
    if(create) TextPrompt(if(cloud) "新建云端收藏夹" else "新建收藏夹", "名称", onDismiss = { create = false }) { name ->
        if(cloud) c.action { c.api.post(if(book.ref.isWenku) "user/favored-wenku" else "user/favored-web", mapOf("title" to name)); version++ }
        else { c.addLocalFavorite(book, name); dismiss() }
    }
}

@Composable private fun LocalFavoriteOptions(c: AppController, book: BookCard, state: LibraryState, dismiss: () -> Unit, create: () -> Unit) {
    val currentFolder = state.books.firstOrNull { it.book.ref == book.ref }?.folder
    AppLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(state.orderedLocalFavoriteFolders(), key = { it }) { folder ->
            val selected = folder == currentFolder
            MenuRow(folder, if(selected) "当前本地收藏夹" else "本地收藏",
                if(selected) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder, { c.addLocalFavorite(book, folder); dismiss() })
        }
        if(currentFolder != null) item {
            MenuRow("取消本地收藏",
                if(book.ref.isLocal && state.deleteLocalCopyOnShelfRemoval) "同时删除导入副本，无法撤销；原文件不受影响" else "保留文件和阅读记录，移除后可撤销",
                Icons.Outlined.BookmarkRemove, {
                    val previous = c.store.state.value.books
                    dismiss()
                    c.action {
                        val deleted = c.store.removeShelfBook(book.ref)
                        if(deleted) {
                            c.message("已移出书架并删除「${book.title}」的导入副本")
                        } else if(c.snackbar.showSnackbar("已取消「${book.title}」的本地收藏", actionLabel = "撤销",
                                withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            c.store.update { current -> restoreRemovedShelfBook(current, previous, book.ref) }
                        }
                    }
                })
        }
        item { MenuRow("新建收藏夹", "创建并收藏这本书", Icons.Outlined.CreateNewFolder, create) }
    }
}

@Composable private fun CloudFavoriteOptions(
    c: AppController, book: BookCard, state: LibraryState, account: String, version: Int, dismiss: () -> Unit, create: () -> Unit,
) {
    val detailKey = c.detailKey(if(book.ref.isWenku) "wenku/${book.ref.id}" else "novel/${book.ref.key}")
    val cachedFolders = c.uiResults.folders.snapshot(c.foldersKey())?.takeIf { it.fresh }
    val cachedDetail = if(book.ref.isWenku) c.uiResults.wenkuDetails.snapshot(detailKey)?.takeIf { it.fresh }?.let { it.value.favored to true }
        else c.uiResults.webDetails.snapshot(detailKey)?.takeIf { it.fresh }?.let { it.value.favored to true }
    AsyncContent(detailKey, refreshKey = listOf(version, state.syncStatus[account]?.lastSuccessAt),
        initialResult = if(cachedFolders != null && cachedDetail != null) cachedFolders.value to cachedDetail.first else null, load = {
            val binding = c.session.capture()
            val folders = c.folders()
            // 保存的 BookCard 可能来自旧账号，收藏归属应读取当前会话的详情缓存。
            val favored = if(book.ref.isWenku) c.wenkuDetail(book.ref).favored else c.webDetail(book.ref).favored
            c.session.ensureCurrent(binding)
            folders to favored
        }) { (data, favored), _ ->
        val originalFolders = if(book.ref.isWenku) data.favoredWenku else data.favoredWeb
        val order = detailKey.binding.account?.let { state.cloudFavoriteFolderOrder(it, book.ref.isWenku) }
        val folders = remember(originalFolders, order) { order?.ordered(originalFolders, CLOUD_DEFAULT_FOLDER_ID) ?: originalFolders }
        val favoriteState = bookFavoriteState(book.ref, false, favored, account, state.pending)
        val path = if(book.ref.isWenku) "user/favored-wenku" else "user/favored-web"
        val bookId = if(book.ref.isWenku) book.ref.id else book.ref.key
        AppLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if(folders.isEmpty()) item { Text("创建一个收藏夹，继续收藏这本书。", Modifier.padding(20.dp)) }
            items(folders, key = { it.id }) { folder ->
                val selected = folder.id == favoriteState.cloudFolder
                MenuRow(folder.title, if(selected) "当前云端收藏夹" else "与原站同步",
                    if(selected) Icons.Outlined.BookmarkAdded else Icons.Outlined.CloudQueue, {
                        c.action {
                            val queued = c.addCloudFavorite(book, folder, detailKey.binding)
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
            item { MenuRow("新建云端收藏夹", "创建后可在这里选择", Icons.Outlined.CreateNewFolder, create) }
        }
    }
}
