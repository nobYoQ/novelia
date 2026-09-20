@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*

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
    var expanded by rememberSaveable { mutableStateOf(true) }
    // A row-level or background retry can finish without this screen's own menu callback.
    // Refresh remote results after success while AsyncContent keeps the current viewport.
    val refreshKey = listOf(version, local.syncStatus[account]?.lastSuccessAt ?: 0L)
    LaunchedEffect(local.autoCollapseCloudFilters) { if(!local.autoCollapseCloudFilters) expanded = true }
    val focus = LocalFocusManager.current
    val path = if (kind == 0) "user/favored-web" else "user/favored-wenku"
    val filter = CloudWebFilter(submitted, source, type, level, translate)
    fun submit() { submitted = query.trim(); page = 0; focus.clearFocus() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val filterHeight = maxHeight * .55f
        AsyncContent(account, load = { c.api.get<CloudFolders>("user/favored") }, refreshKey = refreshKey) { folders, refresh ->
            val realFolders = if (kind == 0) folders.favoredWeb else folders.favoredWenku
            val choices = cloudFolderChoices(realFolders)
            val current = choices.find { it.id == folderId } ?: choices.firstOrNull()
            val editable = current?.takeUnless { it.id == ALL_CLOUD_FAVORITES }
            val selectedSources = source.split(',').filter(String::isNotBlank).toSet()
            val summary = buildList {
                add(current?.title ?: "未创建收藏夹")
                if (kind == 0) {
                    if (submitted.isNotBlank()) add("搜索：$submitted")
                    if (selectedSources.size != providers.size) add("${selectedSources.size} 个书源")
                    if (type != 0) add(listOf("全部", "连载中", "已完结", "短篇")[type])
                    if (level != 0) add(listOf("全部", "一般向", "R18")[level])
                    if (translate != 0) add(listOf("全部", "GPT", "Sakura")[translate])
                }
                add(if (sort == "update") "更新时间" else "收藏时间")
            }.joinToString(" · ")
            Column(Modifier.fillMaxSize()) {
                CollapsibleCloudFilters(expanded, { expanded = !expanded }, summary, filterHeight) {
                    ChoiceRow("收藏类型", listOf("网络小说", "文库小说"), kind) { kind = it; folderId = ""; page = 0 }
                    Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { folder -> FilterChip(current?.id == folder.id, { folderId = folder.id; page = 0 }, label = { Text(folder.title) }) }
                        AssistChip(onClick = { create = true }, label = { Text("新建") }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                    }
                    FlowRow(Modifier.padding(horizontal = 12.dp)) {
                        if (editable != null) {
                            TextButton(onClick = { rename = editable }) { Text("重命名") }
                            if (editable.id != "default") TextButton(onClick = { deleting = editable }) { Text("删除收藏夹") }
                        }
                        TextButton(onClick = refresh) { Text("刷新收藏夹") }
                    }
                    if (kind == 0) {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索中 / 日标题或作者") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
                            trailingIcon = { IconButton(onClick = ::submit) { Icon(Icons.Outlined.Search, "搜索云端收藏") } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("来源（可多选）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { source = providers.keys.filterNot { it in selectedSources }.joinToString(","); page = 0 }) { Text("反选") }
                        }
                        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            providers.forEach { (id, title) -> FilterChip(id in selectedSources, {
                                source = selectedSources.toMutableSet().apply { if (!add(id)) remove(id) }.joinToString(","); page = 0
                            }, label = { Text(title) }) }
                        }
                        ChoiceRow("类型", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 }
                        ChoiceRow("分级", listOf("全部", "一般向", "R18"), level) { level = it; page = 0 }
                        ChoiceRow("翻译", listOf("全部", "GPT", "Sakura"), translate) { translate = it; page = 0 }
                    }
                    ChoiceRow("排序", listOf("更新时间", "收藏时间"), if (sort == "update") 0 else 1) { sort = if (it == 0) "update" else "create"; page = 0 }
                    TextButton(onClick = {
                        query = ""; submitted = ""; source = providers.keys.joinToString(","); type = 0; level = 0; translate = 0; sort = "update"; page = 0
                    }, Modifier.padding(horizontal = 12.dp)) { Text("重置筛选") }
                }
                if (current == null) EmptyState("创建第一个云端收藏夹", "收藏夹与原站同步。", action = "新建收藏夹", onAction = { create = true })
                else {
                    val requestKey = listOf(account, kind, current.id, page, sort, if (kind == 0) filter else null)
                    val listState = key(requestKey) { rememberLazyListState() }
                    val collapse = rememberCloudFilterCollapse(local.autoCollapseCloudFilters, expanded) { expanded = false }
                    AsyncContent(requestKey, refreshKey = refreshKey, modifier = Modifier.weight(1f), load = {
                        c.api.cloudFavorites(kind == 1, current.id, page, sort, filter)
                    }) { result, retry ->
                        AppLazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(collapse).preserveFilterResultPosition(listState),
                            onPageTurn = { direction -> if(direction > 0 && local.autoCollapseCloudFilters) expanded = false }) {
                            if (result.items.isEmpty()) item {
                                EmptyState("没有匹配的收藏", "可调整筛选、切换收藏夹，或在书籍详情中添加云端收藏。", action = "重新加载", onAction = retry)
                            }
                            items(result.items, key = { it.ref.key }, contentType = { "book" }) { book ->
                                val pending = pendingFavoriteAction(local.pending, account, book.ref)
                                val cancelling = pending?.method == "DELETE"
                                Column {
                                val selectedBook = book.ref.key == selectedBookKey
                                BookRow(book, { onOpenBook(book.ref) }, Modifier.semantics { selected = selectedBook }
                                    .then(if(selectedBook) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier), trailing = {
                                    if(cancelling) TextButton(onClick = { c.action {
                                        val restoreFolder = book.favored?.takeIf { it != ALL_CLOUD_FAVORITES && it.isNotBlank() }
                                            ?: current.id.takeUnless { it == ALL_CLOUD_FAVORITES }
                                        if(restoreFolder != null) {
                                            c.cloudMutation("PUT", "$path/$restoreFolder/${if(kind == 0) book.ref.key else book.ref.id}")
                                            version++
                                        } else { c.pendingFavoriteCloud = true; c.pendingFavorite = book }
                                    } }) { Text("撤销") }
                                    else
                                    IconButton(onClick = { c.action {
                                        // The server deletes by user + novel; `all` is also valid for this route.
                                        val queued = c.cloudMutation("DELETE", "$path/${current.id}/${if (kind == 0) book.ref.key else book.ref.id}")
                                        if(!queued) {
                                            if (result.items.size == 1 && page > 0) page--
                                            version++
                                            c.message("已取消云端收藏")
                                        }
                                    } }) { Icon(Icons.Outlined.BookmarkRemove, "取消云端收藏") }
                                })
                                }
                            }
                            item { PageControls(page, result.pageNumber) { page = it } }
                        }
                    }
                }
            }
        }
    }
    if (create) TextPrompt("新建云端收藏夹", "名称", onDismiss = { create = false }) { title -> c.action { c.api.post(path, mapOf("title" to title)); version++ } }
    rename?.let { folder -> TextPrompt("重命名收藏夹", "名称", folder.title, { rename = null }) { title -> c.action { c.api.put("$path/${folder.id}", mapOf("title" to title)); version++ } } }
    deleting?.let { folder -> ConfirmDialog("删除「${folder.title}」？", "移除该云端收藏夹，小说内容不受影响。", { deleting = null }) {
        c.action { c.api.request("DELETE", "$path/${folder.id}"); folderId = ""; page = 0; version++ }
    } }
}

@Composable internal fun CollapsibleCloudFilters(expanded: Boolean, toggle: () -> Unit, summary: String, maxHeight: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Surface(modifier, tonalElevation = 1.dp) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = if (expanded) "收起筛选" else "展开筛选", onClick = toggle).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (expanded) "筛选云端收藏" else "展开筛选", style = MaterialTheme.typography.labelLarge)
                    Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                FilterPanelExpandIcon(expanded)
            }
            FilterPanelVisibility(expanded) {
                AppScrollColumn(Modifier.heightIn(max = maxHeight), state = scroll) { content() }
            }
        }
    }
}

@Composable internal fun rememberCloudFilterCollapse(enabled: Boolean, expanded: Boolean, collapse: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    val latestCollapse by rememberUpdatedState(collapse)
    return remember(enabled, expanded, threshold) {
        object : NestedScrollConnection {
            var downward = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && expanded && source == NestedScrollSource.UserInput) {
                    downward = if (available.y < 0) downward - available.y else 0f
                    if (downward >= threshold) { downward = 0f; latestCollapse() }
                }
                return Offset.Zero
            }
        }
    }
}
