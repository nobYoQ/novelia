@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import cc.novelia.app.ui.components.AppSelectionChip
import cc.novelia.app.ui.components.AppActionChip
import cc.novelia.app.ui.components.AppChipFlowRow
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuOutline
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.QuickFilter
import cc.novelia.app.ui.components.QuickFilterBar
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.SectionTitle
import cc.novelia.app.ui.components.appHorizontalScroll
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable fun DiscoverScreen(c: AppController, initialQuery: String) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var submitted by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var category by rememberSaveable { mutableIntStateOf(if(initialQuery.isNotBlank()) 1 else 0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var searchRevision by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by remember { mutableStateOf(false) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    var searchListOpen by rememberSaveable { mutableStateOf("") }
    val assistantState = rememberSaveableStateHolder()
    var source by rememberSaveable { mutableStateOf("") }; var type by rememberSaveable { mutableIntStateOf(0) }; var translate by rememberSaveable { mutableIntStateOf(0) }; var sort by rememberSaveable { mutableIntStateOf(0) }
    var webLevel by rememberSaveable { mutableIntStateOf(0) }; var wenkuLevel by rememberSaveable { mutableIntStateOf(0) }
    val reducedMotion = appReducedMotion()
    val local by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val keywords by c.app.keywords.state.collectAsStateWithLifecycle()
    val keywordPersistenceError by c.app.keywords.persistenceError.collectAsStateWithLifecycle()
    fun search() {
        c.store.rememberSearch(query)
        if(category != 2) c.app.keywords.markUsed(SearchExpression.tagsIn(query))
        if(BookLinks.parse(query) != null) c.openLink(query) else {
            page = 0; searchRevision++; submitted = query.trim(); if(category == 0) category = 1
        }
    }
    fun resetFilters() { source = ""; type = 0; translate = 0; sort = 0; webLevel = 0; wenkuLevel = 0; page = 0 }
    val filterSummary = remember(category, source, type, translate, sort, webLevel, wenkuLevel, profile?.canEdit) {
        buildList {
            if(category == 1) {
                if(source.isNotBlank()) add(source.split(',').mapNotNull { providers[it] }.joinToString("、"))
                if(type != 0) add(listOf("全部", "连载中", "已完结", "短篇")[type])
                if(translate != 0) add(listOf("全部译文", "GPT", "Sakura")[translate])
                if(sort != 0) add(listOf("更新时间", "点击量排序", "相关度排序")[sort])
                if(profile?.canEdit == true && webLevel != 0) add(listOf("全部分级", "一般向", "R18")[webLevel])
            } else if(wenkuLevel != 0) add(listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向")[wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)])
        }
    }
    Screen("发现", actions = { if(category == 2) IconButton(onClick = { c.requireLogin { c.go("wenku-new") } }) { Icon(Icons.Outlined.Add, "新建文库条目") }; IconButton(onClick = { c.go("rank") }) { Icon(Icons.Outlined.Leaderboard, "排行榜") } }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(query, { query = it }, label = { Text("书名、作者或书源链接") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Outlined.ArrowForward, "搜索") } }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = MaterialTheme.shapes.extraLarge)
            PrimaryTabRow(category) { listOf("为你发现", "网络小说", "文库小说").forEachIndexed { i, label -> Tab(category == i, onClick = { category = i; page = 0 }, text = { Text(label) }) } }
            MotionContent(category, Modifier.weight(1f), animateInitial = false) {
                Column(Modifier.fillMaxSize()) {
                    if(category == 0) {
                        AsyncContent(listOf("recommend", profile?.username, local.blockedAuthors), load = { coroutineScope { val web = async { c.api.webList(0, sort = 1) }; val wenku = async { c.api.wenkuList(0) }; enrichAuthors(web.await().items.map { it.card() }, c, local.blockedAuthors) to enrichAuthors(wenku.await().items.map { it.card() }, c, local.blockedAuthors) } }) { (web, wenku), refresh ->
                            val visibleWeb = remember(web, local.blockedBooks, local.blockedTags, local.blockedAuthors) { web.asSequence().filter { visibleBook(it, local) }.take(8).toList() }
                            val visibleWenku = remember(wenku, local.blockedBooks, local.blockedTags, local.blockedAuthors) { wenku.asSequence().filter { visibleBook(it, local) }.take(6).toList() }
                            AppLazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                                item(key = "rank-hero", contentType = "hero") {
                                    Card(Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("热门小说排行榜", style = MaterialTheme.typography.headlineLarge)
                                            Text("查看各书源榜单，快速挑选想读的小说。", style = MaterialTheme.typography.bodyMedium)
                                            FilledTonalButton(onClick = { c.go("rank") }) { Icon(Icons.Outlined.Leaderboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("看看排行榜") }
                                        }
                                    }
                                }
                                if(local.recentSearches.isNotEmpty()) {
                                    item(key = "recent-searches", contentType = "searches") {
                                        Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
                                            SectionTitle("最近搜索", "清空") { c.store.update { it.copy(recentSearches = emptyList()) } }
                                            Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { local.recentSearches.take(8).forEach { value -> AppActionChip(onClick = { query = value; search() }, label = { Text(value.take(20)) }) } }
                                        }
                                    }
                                }
                                if(local.savedSearches.isNotEmpty()) item(key = "saved-searches", contentType = "searches") { SectionTitle("保存的搜索"); Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { local.savedSearches.forEach { value -> AppSelectionChip(true, onClick = { query = value; search() }, label = { Text(value.take(20)) }, trailingIcon = { IconButton(onClick = { c.store.update { it.copy(savedSearches = it.savedSearches - value) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "删除搜索", Modifier.size(16.dp)) } }) } } }
                                item(key = "web-heading", contentType = "heading") { SectionTitle("热门网络小说", "更多") { category = 1; sort = 1 } }
                                items(visibleWeb, key = { "web-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)), showReadingProgress = false) }
                                item(key = "wenku-heading", contentType = "heading") { SectionTitle("文库新近更新", "更多") { category = 2 } }
                                items(visibleWenku, key = { "wenku-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)), showReadingProgress = false) }
                                item(key = "refresh", contentType = "controls") { TextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新推荐") } }
                            }
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            val compact = maxWidth < 310.dp
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if(category == 1) {
                                    if(compact) IconButton(onClick = { assistantOpen = true }, Modifier.weight(1f).testTag("open-search-assistant")) {
                                        Icon(Icons.Outlined.Search, "辅助搜索")
                                    } else TextButton(onClick = { assistantOpen = true }, Modifier.weight(1f).testTag("open-search-assistant")) {
                                        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                                        Text(" 辅助搜索", maxLines = 1)
                                    }
                                } else Text(if(submitted.isBlank()) "浏览全部" else "搜索结果", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = { searchListOpen = "recent" }) { Icon(Icons.Outlined.History, "查看最近搜索") }
                                IconButton(onClick = { searchListOpen = "saved" }) { Icon(Icons.Outlined.Bookmarks, "查看保存的搜索") }
                                IconButton(onClick = { if(submitted.isNotBlank()) { c.store.update { it.copy(savedSearches = (it.savedSearches + submitted).distinct()) }; c.message("已保存搜索条件") } }, enabled = submitted.isNotBlank()) {
                                    Crossfade(submitted.isNotBlank() && submitted in local.savedSearches, animationSpec = tween(if(reducedMotion) 0 else AppMotion.Quick), label = "savedSearch") { isSaved ->
                                        Icon(if(isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, if(isSaved) "搜索已保存" else "保存搜索")
                                    }
                                }
                                if(compact) IconButton(onClick = { filterOpen = true }) { Icon(Icons.Outlined.Tune, "筛选${if(filterSummary.isEmpty()) "" else " ${filterSummary.size}"}") }
                                else TextButton(onClick = { filterOpen = true }) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Text(if(filterSummary.isEmpty()) " 筛选" else " 筛选 ${filterSummary.size}") }
                            }
                        }
                        if(submitted.isNotBlank()) Text("搜索：$submitted", Modifier.fillMaxWidth().padding(horizontal = 20.dp), maxLines = 1,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if(category == 1 && keywordPersistenceError != null) Text(keywordPersistenceError!!, Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if(category == 1) QuickFilterBar(buildList {
                            add(QuickFilter("排序", listOf("更新", "点击", "相关"), sort) { sort = it; page = 0 })
                            add(QuickFilter("状态", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 })
                            if(profile?.canEdit == true) add(QuickFilter("分级", listOf("全部", "一般向", "R18"), webLevel.coerceIn(0, 2)) { webLevel = it; page = 0 })
                        })
                        if(filterSummary.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(filterSummary.joinToString(" · "), Modifier.weight(1f).testTag("discover-filter-summary"), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = ::resetFilters) { Text("清空筛选") }
                        }
                        val effectiveWebLevel = if(profile?.canEdit == true) webLevel.coerceIn(0, 2) else 1
                        val effectiveWenkuLevel = wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)
                        val requestKey = listOf(category, page, submitted, source, type, translate, sort, effectiveWebLevel,
                            effectiveWenkuLevel, profile?.username, profile?.canEdit, local.blockedAuthors, searchRevision)
                        AsyncContent(requestKey, load = {
                            if(category == 1) c.api.webList(page, submitted, source, type, effectiveWebLevel, translate, sort).let {
                                Page(it.pageNumber, enrichAuthors(it.items.map(WebOutline::card), c, local.blockedAuthors))
                            } else c.api.wenkuList(page, submitted, effectiveWenkuLevel).let {
                                Page(it.pageNumber, enrichAuthors(it.items.map(WenkuOutline::card), c, local.blockedAuthors))
                            }
                        }, initialResult = c.discoverPage?.takeIf { it.first == requestKey }?.second,
                            onLoaded = { c.discoverPage = requestKey to it }) { result, refresh ->
                            val books = remember(result.items, local.blockedBooks, local.blockedTags, local.blockedAuthors) { result.items.filter { visibleBook(it, local) } }
                            val resultScroll = rememberLazyListState()
                            AppLazyColumn(state = resultScroll) {
                                if(books.isEmpty()) item {
                                    EmptyState("没有找到匹配的作品", if(filterSummary.isNotEmpty()) "先放宽筛选条件，搜索关键词会保留。" else "试试较短的关键词，或检查屏蔽条件。", Icons.Outlined.SearchOff,
                                        if(filterSummary.isNotEmpty()) "放宽筛选" else if(submitted.isNotBlank()) "浏览全部作品" else "重新加载",
                                        { if(filterSummary.isNotEmpty()) resetFilters() else if(submitted.isNotBlank()) { query = ""; submitted = ""; page = 0 } else refresh() }, sticker = MidoriSticker.Curious)
                                    if(local.blockedBooks.isNotEmpty() || local.blockedTags.isNotEmpty() || local.blockedAuthors.isNotEmpty()) TextButton(onClick = { c.go("blocked") }, Modifier.fillMaxWidth()) { Text("检查屏蔽条件") }
                                }
                                items(books, key = { it.ref.key }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)), showReadingProgress = false) }
                                item { PageControls(page, result.pageNumber) { page = it } }
                            }
                        }
                    }
                }
            }
        }
    }
    if(filterOpen) AppSheet(onDismissRequest = { filterOpen = false }) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 24.dp)) {
            Text("筛选作品", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = ::resetFilters, Modifier.padding(horizontal = 12.dp)) { Text("重置全部筛选") }
            if(category == 1) {
                val selectedSources = remember(source) { source.split(',').filter(String::isNotEmpty).toSet() }
                Text("书源（可多选）", Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge)
                AppChipFlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { providers.forEach { (id, title) -> AppSelectionChip(id in selectedSources, onClick = { source = selectedSources.toMutableSet().apply { if(!add(id)) remove(id) }.joinToString(","); page = 0 }, label = { Text(title) }) } }
                ChoiceRow("连载状态", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 }
                ChoiceRow("已有译文", listOf("全部", "GPT", "Sakura"), translate) { translate = it; page = 0 }
                ChoiceRow("排序", listOf("更新", "点击", "相关"), sort) { sort = it; page = 0 }
                if(profile?.canEdit == true) ChoiceRow("分级", listOf("全部", "一般向", "R18"), webLevel.coerceIn(0, 2)) { webLevel = it; page = 0 }
            } else ChoiceRow("文库分类", if(profile?.canEdit == true) listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向") else listOf("全部小说", "轻小说", "轻文学", "文学", "非小说"), wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)) { wenkuLevel = it; page = 0 }
            if(category == 1) FilledTonalButton(onClick = { filterOpen = false; assistantOpen = true }, Modifier.padding(horizontal = 20.dp)) { Text("打开辅助搜索") }
            Text("搜索框支持原站查询表达式。规则与示例可在站内使用教程中查看。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { c.go("article/64f3d63f794cbb1321145c07"); filterOpen = false }, Modifier.padding(horizontal = 12.dp)) { Text("查看搜索语法") }
            Button(onClick = { filterOpen = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("查看结果") }
        }
    }
    if(assistantOpen) AppSheet(onDismissRequest = { assistantOpen = false }) {
        assistantState.SaveableStateProvider("discover-assistant") {
            SearchAssistantPanel(query, keywords.entries, true, { assistantOpen = it },
                onApply = { expression -> query = expression; search() },
                onSaveTranslation = c.app.keywords::setTranslation,
                onHelp = { assistantOpen = false; c.go("article/64f3d63f794cbb1321145c07") },
                persistenceError = keywordPersistenceError, categoryNames = keywords.categories,
                libraryActions = rememberKeywordLibraryActions(c.app.keywords), sheetMode = true)
        }
    }
    if(searchListOpen.isNotEmpty()) AppSheet(onDismissRequest = { searchListOpen = "" }) {
        val recent = searchListOpen == "recent"
        val searches = if(recent) local.recentSearches else local.savedSearches
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if(recent) "最近搜索" else "保存的搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if(recent && searches.isNotEmpty()) TextButton(onClick = { c.store.update { it.copy(recentSearches = emptyList()) } }) { Text("清空") }
            TextButton(onClick = { searchListOpen = "" }) { Text("关闭") }
        }
        AppScrollColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentModifier = Modifier.padding(bottom = 24.dp)) {
            if(searches.isEmpty()) Text(if(recent) "还没有搜索记录" else "还没有保存的搜索", Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            searches.forEach { value ->
                MenuRow(value, "点击搜索", if(recent) Icons.Outlined.History else Icons.Outlined.Bookmark,
                    { query = value; searchListOpen = ""; search() },
                    trailing = if(recent) null else {{ IconButton(onClick = { c.store.update { it.copy(savedSearches = it.savedSearches - value) } }) {
                        Icon(Icons.Outlined.Close, "删除保存的搜索")
                    } }})
            }
        }
    }
}

/** 列表摘要可能没有作者，仅在启用作者屏蔽时补取作者信息。 */
internal suspend fun enrichAuthors(books: List<BookCard>, c: AppController, blockedAuthors: Set<String>): List<BookCard> {
    if(blockedAuthors.isEmpty()) return books
    val limit = Semaphore(4)
    return coroutineScope {
        books.map { book -> async {
            if(book.authors.isNotEmpty()) book else limit.withPermit {
                try {
                    val authors = if(book.ref.isWenku) c.detail<WenkuDetail>("wenku/${book.ref.id}").authors
                        else c.detail<WebDetail>("novel/${book.ref.key}").authors.map { it.name }
                    book.copy(authors = authors)
                } catch(e: CancellationException) { throw e }
                  catch(_: Exception) { book }
            }
        } }.map { it.await() }
    }
}
