@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.library.removeCloudFavorites
import cc.novelia.app.data.library.withCloudFavoritesAddedLocally
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.network.ALL_CLOUD_FAVORITES
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.data.network.cloudFavorites
import cc.novelia.app.data.network.cloudFolderChoices
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.CachedAsyncContent
import cc.novelia.app.ui.components.base.rememberCachedListState
import cc.novelia.app.ui.components.book.rememberCloudBookMetadata
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.TextPrompt
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.navigation.AppController

@Composable fun CloudShelf(c: AppController, onOpenBook: (BookRef) -> Unit = c::book, selectedBookKey: String? = null, active: Boolean = true, onSelectionStateChanged: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    if (profile == null) {
        EmptyState("连接你的云端书架", "登录原站账号，访问网络小说和文库收藏。", Icons.Outlined.CloudQueue, "登录", { c.go("login") })
        return
    }
    key(profile!!.username) { CloudShelfAccount(c, profile!!.username, onOpenBook, selectedBookKey, active, onSelectionStateChanged) }
}

@Composable private fun CloudShelfAccount(c: AppController, account: String, onOpenBook: (BookRef) -> Unit, selectedBookKey: String?, active: Boolean, onSelectionStateChanged: (Boolean, Boolean) -> Unit) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val binding = c.session.capture()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var folderId by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var version by rememberSaveable { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Folder?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }
    var sort by rememberSaveable { mutableStateOf("update") }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(providers.keys.joinToString(",")) }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var level by rememberSaveable { mutableIntStateOf(0) }
    var translate by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(mapOf<String, BookCard>()) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkProgress by remember { mutableIntStateOf(0) }
    var bulkLocal by remember { mutableStateOf<List<BookCard>?>(null) }
    var bulkRemoval by remember { mutableStateOf<Pair<String, List<BookCard>>?>(null) }
    SideEffect { onSelectionStateChanged(active && managing, bulkBusy) }
    DisposableEffect(onSelectionStateChanged) { onDispose { onSelectionStateChanged(false, false) } }
    BackHandler(active && managing) { if(!bulkBusy) { managing = false; selection = emptyMap() } }
    LaunchedEffect(active) {
        if(!active) { managing = false; selection = emptyMap(); expanded = false; bulkLocal = null; bulkRemoval = null }
    }
    // 行内重试或后台重试可能独立于本页菜单回调完成；
    // 成功后刷新远端结果，由 AsyncContent 保留当前视口。
    val refreshKey = version
    val foldersKey = listOf(binding, cacheGeneration)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { expanded = false }
    val focus = LocalFocusManager.current
    val path = if (kind == 0) "user/favored-web" else "user/favored-wenku"
    val filter = CloudWebFilter(submitted, source, type, level, translate)
    fun submit() { if(!bulkBusy) { submitted = query.trim(); page = 0; focus.clearFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val filterHeight = maxHeight * .9f
        // 已有收藏夹时静默更新辅助数据，仅书籍列表显示刷新提示，避免上下两条加载条。
        CachedAsyncContent(c.uiResults.folders, foldersKey, load = { c.api.get<CloudFolders>("user/favored") }, refreshKey = refreshKey,
            observeInvalidation = !bulkBusy, validate = { c.session.ensureCurrent(binding) }, showRefreshIndicator = false) { folders, _ ->
            val realFolders = if (kind == 0) folders.favoredWeb else folders.favoredWenku
            val choices = cloudFolderChoices(realFolders)
            val current = choices.find { it.id == folderId } ?: choices.firstOrNull()
            LaunchedEffect(kind, current?.id, sort, filter) {
                managing = false; selection = emptyMap(); bulkLocal = null; bulkRemoval = null
            }
            val editable = current?.takeUnless { it.id == ALL_CLOUD_FAVORITES }
            val requestKey = listOf(binding, cacheGeneration, kind, current?.id, page, sort, if(kind == 0) filter else null)
            val cachedPage = c.uiResults.cloudPages.snapshot(requestKey)?.value
            var pageCount by remember(requestKey) { mutableStateOf(cachedPage?.items?.size) }
            var controlsHeight by remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                CompactCloudControls(choices, current, { folderId = it; page = 0 }, sort, { sort = it; page = 0 }, kind, filter,
                    onApply = { nextKind, next ->
                        if(kind != nextKind) { kind = nextKind; folderId = ""; query = ""; submitted = "" }
                        source = next.source; type = next.type; level = next.level; translate = next.translate; page = 0
                    }, query = query, onQuery = { query = it }, onSubmit = ::submit,
                    onClearQuery = { query = ""; submitted = ""; page = 0 }, expanded = expanded, onExpanded = { expanded = it },
                    countLabel = pageCount?.let { "本页 $it 本" } ?: "加载中", maxHeight = filterHeight, active = active,
                    visible = !managing, modifier = Modifier.onSizeChanged { controlsHeight = it.height },
                    grid = local.cloudShelfGrid, onToggleLayout = { c.store.update { it.copy(cloudShelfGrid = !it.cloudShelfGrid) } }) { close ->
                    DropdownMenuItem({ Text("新建收藏夹") }, { close(); create = true }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                    if(editable != null) {
                        DropdownMenuItem({ Text("重命名收藏夹") }, { close(); rename = editable })
                        if(editable.id != "default") DropdownMenuItem({ Text("删除收藏夹") }, { close(); deleting = editable })
                    }
                    DropdownMenuItem({ Text("刷新收藏夹") }, { close(); version++ }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                }
                if(current == null) EmptyState("创建第一个云端收藏夹", "收藏夹与原站同步。", action = "新建收藏夹", onAction = { create = true })
                else {
                    val listState = rememberCachedListState(c.uiResults.cloudPages, requestKey)
                    val gridState = rememberCachedListState(c.uiResults.cloudPages, requestKey, "grid")
                    CachedAsyncContent(c.uiResults.cloudPages, requestKey, refreshKey = refreshKey, modifier = Modifier.weight(1f), load = {
                        c.api.cloudFavorites(kind == 1, current.id, page, sort, filter)
                    }, observeInvalidation = !bulkBusy, validate = { c.session.ensureCurrent(binding) },
                        onLoaded = { pageCount = it.items.size }) { result, retry ->
                        LaunchedEffect(result.items, account) {
                            if(c.session.profile.value?.username != account) return@LaunchedEffect
                            c.store.update { it.withCloudReadingMetadata(result.items, account) }
                        }
                        CloudShelfBooks(result.items, local, account, listState, page, result.pageNumber, { page = it },
                            managing, { managing = it; if(it) { expanded = false; focus.clearFocus() } }, selection, { selection = it },
                            bulkBusy, bulkProgress, active, controlsHeight, selectedBookKey,
                            onOpenBook = { expanded = false; onOpenBook(it) }, onLocal = { bulkLocal = selection.values.toList() },
                            onRemove = { bulkRemoval = current.id to selection.values.toList() }, onRetry = retry, grid = local.cloudShelfGrid, gridState = gridState,
                            metadata = { rememberCloudBookMetadata(c, it, account, refreshKey) }, actions = { displayed, canAct ->
                                val book = displayed
                                val cancelling = pendingFavoriteAction(local.pending, account, book.ref)?.method == "DELETE"
                                if(cancelling) AppTextButton(onClick = { if(canAct()) c.action {
                                    val restoreFolder = book.favored?.takeIf { it != ALL_CLOUD_FAVORITES && it.isNotBlank() }
                                        ?: current.id.takeUnless { it == ALL_CLOUD_FAVORITES }
                                    if(restoreFolder != null) { c.addCloudFavorite(displayed, restoreFolder); version++ }
                                    else { c.pendingFavoriteCloud = true; c.pendingFavorite = book }
                                } }) { Text("撤销") }
                            })
                    }
                }
            }
        }
    }
    bulkLocal?.let { books -> CloudFavoriteLocalSheet(local.folders, books.size, { bulkLocal = null }) { folder ->
        val added = books.count { book -> c.store.state.value.books.none { it.book.ref == book.ref } }
        c.store.update { it.withCloudFavoritesAddedLocally(books, folder) }
        bulkLocal = null; managing = false; selection = emptyMap()
        c.message("已加入 $added 本到「$folder」" + if(added < books.size) "，${books.size - added} 本已在本地" else "")
    } }
    bulkRemoval?.let { (selectedFolder, books) ->
        ConfirmDialog("取消 ${books.size} 本云端收藏？", "仅取消所选作品的云端收藏，本地收藏和阅读记录会保留。",
            { bulkRemoval = null }, confirmLabel = "取消云端收藏") {
            val binding = c.session.capture()
            // 一批完成后统一刷新，避免每本成功都触发列表与元数据请求。
            bulkBusy = true; bulkProgress = 0; expanded = false
            c.action {
                try {
                    if(binding.account != account) throw SessionChangedException()
                    val result = removeCloudFavorites(books, selectedFolder, { c.session.ensureCurrent(binding) },
                        remove = { c.cloudMutation("DELETE", it, notifyQueued = false) }, onProgress = { bulkProgress = it })
                    selection = selection.filterKeys { it in result.failed }
                    managing = result.failed.isNotEmpty()
                    if(result.removed.isNotEmpty()) { page = 0; version++ }
                    c.message(buildList {
                        if(result.removed.isNotEmpty()) add("已取消 ${result.removed.size} 本")
                        if(result.queued.isNotEmpty()) add("${result.queued.size} 本待同步")
                        if(result.failed.isNotEmpty()) add("${result.failed.size} 本失败，已保留选择：${result.failed.values.first().friendlyMessage()}")
                    }.joinToString("；"))
                } finally { bulkBusy = false }
            }
        }
    }
    if (create) TextPrompt("新建云端收藏夹", "名称", onDismiss = { create = false }) { title -> c.action { c.api.post(path, mapOf("title" to title)); version++ } }
    rename?.let { folder -> TextPrompt("重命名收藏夹", "名称", folder.title, { rename = null }) { title -> c.action { c.api.put("$path/${folder.id}", mapOf("title" to title)); version++ } } }
    deleting?.let { folder -> ConfirmDialog("删除「${folder.title}」？", "移除该云端收藏夹，小说内容不受影响。", { deleting = null }, confirmLabel = "删除收藏夹") {
        c.action { c.api.request("DELETE", "$path/${folder.id}"); folderId = ""; page = 0; version++ }
    } }
}
