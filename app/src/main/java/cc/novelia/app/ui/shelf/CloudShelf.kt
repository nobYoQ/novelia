@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.AppCheckbox

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppIconButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import cc.novelia.app.ui.components.AppSelectionChip
import cc.novelia.app.ui.components.AppChipFlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.bookRowStatus
import cc.novelia.app.ui.components.rememberCloudBookMetadata
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.CollapsibleCloudFilters
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.TextPrompt
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController

@Composable fun CloudShelf(c: AppController, onOpenBook: (BookRef) -> Unit = c::book, selectedBookKey: String? = null) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    if (profile == null) {
        EmptyState("连接你的云端书架", "登录原站账号，访问网络小说和文库收藏。", Icons.Outlined.CloudQueue, "登录", { c.go("login") })
        return
    }
    key(profile!!.username) { CloudShelfAccount(c, profile!!.username, onOpenBook, selectedBookKey) }
}

@Composable private fun CloudShelfAccount(c: AppController, account: String, onOpenBook: (BookRef) -> Unit, selectedBookKey: String?) {
    val local by c.store.state.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var folderId by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
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
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(mapOf<String, BookCard>()) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkRefreshAt by remember { mutableLongStateOf(0L) }
    var bulkProgress by remember { mutableIntStateOf(0) }
    var bulkLocal by remember { mutableStateOf<List<BookCard>?>(null) }
    var bulkRemoval by remember { mutableStateOf<Pair<String, List<BookCard>>?>(null) }
    fun toggleSelection(book: BookCard) {
        if(!bulkBusy) selection = if(book.ref.key in selection) selection - book.ref.key else selection + (book.ref.key to book)
    }
    BackHandler(managing && !bulkBusy) { managing = false; selection = emptyMap() }
    // 行内重试或后台重试可能独立于本页菜单回调完成；
    // 成功后刷新远端结果，由 AsyncContent 保留当前视口。
    val refreshKey = listOf(version, if(bulkBusy) bulkRefreshAt else local.syncStatus[account]?.lastSuccessAt ?: 0L)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { expanded = false }
    val focus = LocalFocusManager.current
    val collapseControls = { controlsExpanded = false; expanded = false; focus.clearFocus() }
    val path = if (kind == 0) "user/favored-web" else "user/favored-wenku"
    val filter = CloudWebFilter(submitted, source, type, level, translate)
    fun submit() { if(!bulkBusy) { submitted = query.trim(); page = 0; focus.clearFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val filterHeight = maxHeight * .55f
        AsyncContent(account, load = { c.api.get<CloudFolders>("user/favored") }, refreshKey = refreshKey) { folders, refresh ->
            val realFolders = if (kind == 0) folders.favoredWeb else folders.favoredWenku
            val choices = cloudFolderChoices(realFolders)
            val current = choices.find { it.id == folderId } ?: choices.firstOrNull()
            LaunchedEffect(kind, current?.id, sort, filter) {
                managing = false; selection = emptyMap(); bulkLocal = null; bulkRemoval = null
            }
            val editable = current?.takeUnless { it.id == ALL_CLOUD_FAVORITES }
            val selectedSources = source.split(',').filter(String::isNotBlank).toSet()
            val activeFilters = buildList {
                if (kind == 0) {
                    if (selectedSources.size != providers.size) add("${selectedSources.size} 个书源")
                    if (type != 0) add(listOf("全部", "连载中", "已完结", "短篇")[type])
                    if (level != 0) add(listOf("全部", "一般向", "R18")[level])
                    if (translate != 0) add(listOf("全部", "GPT", "Sakura")[translate])
                }
            }
            val summary = activeFilters.joinToString(" · ")
            val searchSummary = if(kind == 0 && submitted.isNotBlank()) listOf("搜索：$submitted") else emptyList()
            val requestKey = listOf(account, kind, current?.id, page, sort, if(kind == 0) filter else null)
            var pageCount by remember(requestKey) { mutableStateOf<Int?>(null) }
            Column(Modifier.fillMaxSize()) {
                ShelfControlsPanel(
                    (listOf(if(kind == 0) "网络小说" else "文库小说", current?.title ?: "收藏夹") + searchSummary + activeFilters +
                        if(sort == "update") "更新时间" else "收藏时间").joinToString(" · "),
                    controlsExpanded, { if(controlsExpanded) collapseControls() else controlsExpanded = true }, "cloud", headerActions = {
                        if(kind == 0) ShelfSearchToggle(searchExpanded, submitted.isNotBlank(),
                            { searchExpanded = !searchExpanded; focus.clearFocus() }, "cloud", enabled = !bulkBusy)
                    }) {
                    CloudNovelKindSwitch(kind) { if(!bulkBusy) { kind = it; folderId = ""; page = 0; expanded = false; searchExpanded = false; focus.clearFocus() } }
                    key(kind) { CloudShelfToolbar(choices, current, { if(!bulkBusy) { folderId = it; page = 0 } },
                        sort, { if(!bulkBusy) { sort = it; page = 0 } }, activeFilters.size, expanded,
                        if(kind == 0) ({ if(!bulkBusy) { expanded = !expanded; focus.clearFocus() } }) else null) { close ->
                        DropdownMenuItem({ Text("新建收藏夹") }, { close(); create = true }, enabled = !bulkBusy, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                        if(editable != null) {
                            DropdownMenuItem({ Text("重命名收藏夹") }, { close(); rename = editable }, enabled = !bulkBusy)
                            if(editable.id != "default") DropdownMenuItem({ Text("删除收藏夹") }, { close(); deleting = editable }, enabled = !bulkBusy)
                        }
                        DropdownMenuItem({ Text("刷新收藏夹") }, { close(); refresh() }, enabled = !bulkBusy, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                    } }
                    if(activeFilters.isNotEmpty()) Text(summary,
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp).testTag("cloud-filter-summary"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    CollapsibleCloudFilters(expanded && !bulkBusy, { expanded = !expanded }, summary, filterHeight, showHeader = false) {
                        if (kind == 0) {
                            Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("来源（可多选）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                AppTextButton(onClick = { source = providers.keys.filterNot { it in selectedSources }.joinToString(","); page = 0 }) { Text("反选") }
                            }
                            AppChipFlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                                providers.forEach { (id, title) -> AppSelectionChip(id in selectedSources, {
                                    source = selectedSources.toMutableSet().apply { if (!add(id)) remove(id) }.joinToString(","); page = 0
                                }, label = { Text(title) }) }
                            }
                            ChoiceRow("类型", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 }
                            ChoiceRow("分级", listOf("全部", "一般向", "R18"), level) { level = it; page = 0 }
                            ChoiceRow("翻译", listOf("全部", "GPT", "Sakura"), translate) { translate = it; page = 0 }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            AppTextButton(onClick = {
                                source = providers.keys.joinToString(","); type = 0; level = 0; translate = 0; page = 0
                            }, Modifier.heightIn(min = 48.dp)) { Text("重置筛选") }
                            AppTextButton(onClick = { expanded = false; focus.clearFocus() }, Modifier.heightIn(min = 48.dp)) { Text("完成") }
                        }
                    }
                }
                ShelfSearchField(searchExpanded && kind == 0, query, { query = it }, onSubmit = ::submit,
                    onClear = { query = ""; submitted = ""; page = 0 }, label = "搜索中 / 日标题或作者", tagPrefix = "cloud", enabled = !bulkBusy)
                ShelfBatchHeader(
                    if(managing) "已选 ${selection.size} 本" else pageCount?.let { "本页 $it 本" } ?: "收藏管理",
                    managing, { managing = !managing; selection = emptyMap(); expanded = false; focus.clearFocus() }, "cloud",
                    manageEnabled = !bulkBusy && (managing || (pageCount ?: 0) > 0))
                if (current == null) EmptyState("创建第一个云端收藏夹", "收藏夹与原站同步。", action = "新建收藏夹", onAction = { create = true })
                else {
                    val listState = key(requestKey) { rememberLazyListState() }
                    AsyncContent(requestKey, refreshKey = refreshKey, modifier = Modifier.weight(1f), load = {
                        c.api.cloudFavorites(kind == 1, current.id, page, sort, filter)
                    }, onLoaded = { pageCount = it.items.size }) { result, retry ->
                        LaunchedEffect(result.items, account) {
                            if(c.session.profile.value?.username != account) return@LaunchedEffect
                            c.store.update { it.withCloudReadingMetadata(result.items, account) }
                        }
                        Column(Modifier.fillMaxSize()) {
                            val pageKeys = result.items.map { it.ref.key }.toSet()
                            val allOnPageSelected = pageKeys.isNotEmpty() && selection.keys.containsAll(pageKeys)
                            CloudFavoriteBatchControls(managing, selection.size, result.items.size, allOnPageSelected,
                                bulkBusy, bulkProgress, onManage = {
                                    managing = !managing; selection = emptyMap(); expanded = false
                                }, onSelectPage = {
                                    selection = if(allOnPageSelected) selection - pageKeys else selection + result.items.associateBy { it.ref.key }
                                }, onClear = { selection = emptyMap() }, onLocal = { bulkLocal = selection.values.toList() },
                                onRemove = { bulkRemoval = current.id to selection.values.toList() }, showHeader = false)
                            // 把滚动锚点保留在视口内，筛选面板改变大小时不滚动书目。
                            AppLazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                if (result.items.isEmpty()) item {
                                    EmptyState("没有匹配的收藏", "可调整筛选、切换收藏夹，或在书籍详情中添加云端收藏。", action = "重新加载", onAction = retry)
                                }
                                items(result.items, key = { it.ref.key }, contentType = { "book" }) { book ->
                                    val displayed = rememberCloudBookMetadata(c, book, account, refreshKey)
                                    val pending = pendingFavoriteAction(local.pending, account, book.ref)
                                    val cancelling = pending?.method == "DELETE"
                                    val selectedBook = if(managing) book.ref.key in selection else book.ref.key == selectedBookKey
                                    BookRow(displayed, { if(managing) toggleSelection(displayed) else { expanded = false; onOpenBook(book.ref) } },
                                        Modifier.testTag("cloud-book-${book.ref.key}").semantics { selected = selectedBook }
                                            .then(if(selectedBook) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
                                        status = bookRowStatus(displayed, local.books.firstOrNull { it.book.ref == book.ref }, local.positions[book.ref.key], local.bookUpdates[book.ref.key], account, preferCloud = true), compactMetadata = true, trailing = {
                                            if(managing) AppCheckbox(book.ref.key in selection, { toggleSelection(displayed) }, enabled = !bulkBusy,
                                                modifier = Modifier.semantics { contentDescription = "选择${book.title}" })
                                            else if(cancelling) AppTextButton(onClick = { c.action {
                                                val restoreFolder = book.favored?.takeIf { it != ALL_CLOUD_FAVORITES && it.isNotBlank() }
                                                    ?: current.id.takeUnless { it == ALL_CLOUD_FAVORITES }
                                                if(restoreFolder != null) {
                                                    c.addCloudFavorite(displayed, restoreFolder)
                                                    version++
                                                } else { c.pendingFavoriteCloud = true; c.pendingFavorite = book }
                                            } }) { Text("撤销") }
                                            else AppIconButton(onClick = { c.action {
                                                // 服务器按用户和小说删除收藏，此路由的收藏夹参数也接受 `all`。
                                                val queued = c.cloudMutation("DELETE", "$path/${current.id}/${if (kind == 0) book.ref.key else book.ref.id}")
                                                if(!queued) {
                                                    if (result.items.size == 1 && page > 0) page--
                                                    version++
                                                    c.message("已取消云端收藏")
                                                }
                                            } }) { Icon(Icons.Outlined.BookmarkRemove, "取消云端收藏") }
                                        })
                                }
                                item { PageControls(page, result.pageNumber) { if(!bulkBusy) page = it } }
                            }
                        }
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
            bulkRefreshAt = local.syncStatus[account]?.lastSuccessAt ?: 0L
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

@Composable internal fun CloudNovelKindSwitch(kind: Int, onChange: (Int) -> Unit) {
    ShelfKindSwitch(listOf("网络小说", "文库小说"), kind, "cloud-novel-kind", onChange)
}
