@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.background
import cc.novelia.app.ui.components.AppSelectionChip
import cc.novelia.app.ui.components.AppChipFlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.library.withCloudReadingMetadata
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
import cc.novelia.app.ui.components.rememberCloudFilterCollapse
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
    var expanded by remember { mutableStateOf(false) }
    // 行内重试或后台重试可能独立于本页菜单回调完成；
    // 成功后刷新远端结果，由 AsyncContent 保留当前视口。
    val refreshKey = listOf(version, local.syncStatus[account]?.lastSuccessAt ?: 0L)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { expanded = false }
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
            val activeFilters = buildList {
                if (kind == 0) {
                    if (submitted.isNotBlank()) add("搜索：$submitted")
                    if (selectedSources.size != providers.size) add("${selectedSources.size} 个书源")
                    if (type != 0) add(listOf("全部", "连载中", "已完结", "短篇")[type])
                    if (level != 0) add(listOf("全部", "一般向", "R18")[level])
                    if (translate != 0) add(listOf("全部", "GPT", "Sakura")[translate])
                }
            }
            val summary = activeFilters.joinToString(" · ")
            Column(Modifier.fillMaxSize()) {
                CloudNovelKindSwitch(kind) { kind = it; folderId = ""; page = 0; expanded = false }
                key(kind) { CloudShelfToolbar(choices, current, { folderId = it; page = 0 },
                    sort, { sort = it; page = 0 }, activeFilters.size, expanded,
                    if(kind == 0) ({ expanded = !expanded; focus.clearFocus() }) else null) { close ->
                    DropdownMenuItem({ Text("新建收藏夹") }, { close(); create = true }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                    if(editable != null) {
                        DropdownMenuItem({ Text("重命名收藏夹") }, { close(); rename = editable })
                        if(editable.id != "default") DropdownMenuItem({ Text("删除收藏夹") }, { close(); deleting = editable })
                    }
                    DropdownMenuItem({ Text("刷新收藏夹") }, { close(); refresh() }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                } }
                if(activeFilters.isNotEmpty()) Text(summary,
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp).testTag("cloud-filter-summary"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                CollapsibleCloudFilters(expanded, { expanded = !expanded }, summary, filterHeight, showHeader = false) {
                    if (kind == 0) {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索中 / 日标题或作者") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
                            trailingIcon = { IconButton(onClick = ::submit) { Icon(Icons.Outlined.Search, "搜索云端收藏") } },
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp))
                        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("来源（可多选）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { source = providers.keys.filterNot { it in selectedSources }.joinToString(","); page = 0 }) { Text("反选") }
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
                        TextButton(onClick = {
                            query = ""; submitted = ""; source = providers.keys.joinToString(","); type = 0; level = 0; translate = 0; page = 0
                        }, Modifier.heightIn(min = 48.dp)) { Text("重置筛选") }
                        TextButton(onClick = { submit(); expanded = false }, Modifier.heightIn(min = 48.dp)) { Text("完成") }
                    }
                }
                if (current == null) EmptyState("创建第一个云端收藏夹", "收藏夹与原站同步。", action = "新建收藏夹", onAction = { create = true })
                else {
                    val requestKey = listOf(account, kind, current.id, page, sort, if (kind == 0) filter else null)
                    val listState = key(requestKey) { rememberLazyListState() }
                    val collapse = rememberCloudFilterCollapse(local.autoCollapseCloudFilters, expanded) { expanded = false }
                    AsyncContent(requestKey, refreshKey = refreshKey, modifier = Modifier.weight(1f), load = {
                        c.api.cloudFavorites(kind == 1, current.id, page, sort, filter)
                    }) { result, retry ->
                        LaunchedEffect(result.items, account) {
                            if(c.session.profile.value?.username != account) return@LaunchedEffect
                            c.store.update { it.withCloudReadingMetadata(result.items, account) }
                        }
                        // 把滚动锚点保留在视口内，筛选面板改变大小时不滚动书目。
                        AppLazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(collapse),
                            onPageTurn = { direction -> if(direction > 0 && local.autoCollapseCloudFilters) expanded = false }) {
                            if (result.items.isEmpty()) item {
                                EmptyState("没有匹配的收藏", "可调整筛选、切换收藏夹，或在书籍详情中添加云端收藏。", action = "重新加载", onAction = retry)
                            }
                            items(result.items, key = { it.ref.key }, contentType = { "book" }) { book ->
                                val displayed = rememberCloudBookMetadata(c, book, account, refreshKey)
                                val pending = pendingFavoriteAction(local.pending, account, book.ref)
                                val cancelling = pending?.method == "DELETE"
                                Column {
                                val selectedBook = book.ref.key == selectedBookKey
                                BookRow(displayed, { expanded = false; onOpenBook(book.ref) }, Modifier.semantics { selected = selectedBook }
                                    .then(if(selectedBook) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
                                    status = bookRowStatus(displayed, local.books.firstOrNull { it.book.ref == book.ref }, local.positions[book.ref.key], local.bookUpdates[book.ref.key], account, preferCloud = true), trailing = {
                                    if(cancelling) TextButton(onClick = { c.action {
                                        val restoreFolder = book.favored?.takeIf { it != ALL_CLOUD_FAVORITES && it.isNotBlank() }
                                            ?: current.id.takeUnless { it == ALL_CLOUD_FAVORITES }
                                        if(restoreFolder != null) {
                                            c.addCloudFavorite(displayed, restoreFolder)
                                            version++
                                        } else { c.pendingFavoriteCloud = true; c.pendingFavorite = book }
                                    } }) { Text("撤销") }
                                    else
                                    IconButton(onClick = { c.action {
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
    deleting?.let { folder -> ConfirmDialog("删除「${folder.title}」？", "移除该云端收藏夹，小说内容不受影响。", { deleting = null }, confirmLabel = "删除收藏夹") {
        c.action { c.api.request("DELETE", "$path/${folder.id}"); folderId = ""; page = 0; version++ }
    } }
}

@Composable internal fun CloudNovelKindSwitch(kind: Int, onChange: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("cloud-novel-kind"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(4.dp)) {
            listOf("网络小说", "文库小说").forEachIndexed { index, label ->
                TextButton(onClick = { if(kind != index) onChange(index) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = kind == index },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if(kind == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        contentColor = if(kind == index) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
