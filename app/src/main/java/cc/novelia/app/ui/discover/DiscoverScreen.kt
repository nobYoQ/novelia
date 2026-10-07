@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

import androidx.compose.animation.core.tween
import cc.novelia.app.ui.components.AppActionChip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.catalog.SavedSearchPreset
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.catalog.withMigratedSearchPresets
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
import cc.novelia.app.ui.components.CharacterCountFilterFields
import cc.novelia.app.ui.components.NovelLocalFilterSaver
import cc.novelia.app.ui.components.QuickFilter
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.PageControls
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
    var searchEditing by rememberSaveable(initialQuery) { mutableStateOf(false) }
    var category by rememberSaveable { mutableIntStateOf(if(initialQuery.isNotBlank()) 1 else 0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var searchRevision by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by remember { mutableStateOf(false) }
    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    var searchListOpen by rememberSaveable { mutableStateOf("") }
    var presetToSave by remember { mutableStateOf<SavedSearchPreset?>(null) }
    val assistantState = rememberSaveableStateHolder()
    var source by rememberSaveable { mutableStateOf("") }; var type by rememberSaveable { mutableIntStateOf(0) }; var translate by rememberSaveable { mutableIntStateOf(0) }; var sort by rememberSaveable { mutableIntStateOf(0) }
    var webLevel by rememberSaveable { mutableIntStateOf(0) }; var wenkuLevel by rememberSaveable { mutableIntStateOf(0) }
    var localFilter by rememberSaveable(stateSaver = NovelLocalFilterSaver) { mutableStateOf(NovelLocalFilter()) }
    val reducedMotion = appReducedMotion()
    val local by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val keywords by c.app.keywords.state.collectAsStateWithLifecycle()
    val keywordIndex by c.app.keywords.displayIndex.collectAsStateWithLifecycle()
    val keywordLabels = keywordIndex.labels
    val keywordPersistenceError by c.app.keywords.persistenceError.collectAsStateWithLifecycle()
    LaunchedEffect(local.savedSearches) { c.store.update { it.withMigratedSearchPresets() } }
    fun currentPreset() = SavedSearchPreset(name = submitted.ifBlank { if(category == 2) "文库小说筛选" else "网络小说筛选" }.take(80),
        query = submitted, category = category, source = source, type = type, translate = translate, sort = sort,
        webLevel = webLevel, wenkuLevel = wenkuLevel, localFilter = localFilter).normalized()
    fun applyPreset(preset: SavedSearchPreset) {
        val value = preset.normalized()
        query = value.query; submitted = value.query; category = value.category
        searchEditing = false
        source = value.source; type = value.type; translate = value.translate; sort = value.sort
        webLevel = value.webLevel; wenkuLevel = value.wenkuLevel; page = 0; searchRevision++
        localFilter = value.localFilter
        c.store.rememberSearch(value.query)
        if(value.category == 1) c.app.keywords.enqueueUsed(SearchExpression.tagsIn(value.query))
    }
    fun search() {
        searchEditing = false
        c.store.rememberSearch(query)
        if(category != 2) c.app.keywords.enqueueUsed(SearchExpression.tagsIn(query))
        if(BookLinks.parse(query) != null) c.openLink(query) else {
            page = 0; searchRevision++; submitted = query.trim(); if(category == 0) category = 1
        }
    }
    fun resetFilters() { source = ""; type = 0; translate = 0; sort = 0; webLevel = 0; wenkuLevel = 0; page = 0; localFilter = NovelLocalFilter() }
    val filterSummary = remember(category, source, translate, webLevel, wenkuLevel, localFilter, profile?.canEdit) {
        buildList {
            if(category == 1) {
                if(source.isNotBlank()) add(source.split(',').mapNotNull { providers[it] }.joinToString("、"))
                if(translate != 0) add(listOf("全部译文", "GPT", "Sakura")[translate])
                if(profile?.canEdit == true && webLevel != 0) add(listOf("全部分级", "一般向", "R18")[webLevel])
                addAll(localFilter.summaries())
            } else if(wenkuLevel != 0) add(listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向")[wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)])
        }
    }
    val filterCount = filterSummary.size + if(category == 1) listOf(type, sort).count { it != 0 } else 0
    DiscoverSearchLayout(query, submitted, searchEditing, { query = it }, { searchEditing = it }, ::search,
        actions = { if(category == 2) IconButton(onClick = { c.requireLogin { c.go("wenku-new") } }) { Icon(Icons.Outlined.Add, "新建文库条目") }; IconButton(onClick = { c.go("rank") }) { Icon(Icons.Outlined.Leaderboard, "排行榜") } }) { onPageTurn ->
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(category) { listOf("为你发现", "网络小说", "文库小说").forEachIndexed { i, label -> Tab(category == i, onClick = { category = i; page = 0 }, text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
            MotionContent(category, Modifier.weight(1f), animateInitial = false) {
                Column(Modifier.fillMaxSize()) {
                    if(category == 0) {
                        AsyncContent(listOf("recommend", profile?.username, local.blockedAuthors), load = { coroutineScope { val web = async { c.api.webList(0, sort = 1) }; val wenku = async { c.api.wenkuList(0) }; enrichAuthors(web.await().items.map { it.card() }, c, local.blockedAuthors) to enrichAuthors(wenku.await().items.map { it.card() }, c, local.blockedAuthors) } }, revealContent = true) { (web, wenku), refresh ->
                            val visibleWeb = remember(web, local.blockedBooks, local.blockedTags, local.blockedAuthors) { web.asSequence().filter { visibleBook(it, local) }.take(8).toList() }
                            val visibleWenku = remember(wenku, local.blockedBooks, local.blockedTags, local.blockedAuthors) { wenku.asSequence().filter { visibleBook(it, local) }.take(6).toList() }
                            AppLazyColumn(contentPadding = PaddingValues(bottom = 20.dp), onPageTurn = onPageTurn) {
                                if(local.recentSearches.isNotEmpty()) {
                                    item(key = "recent-searches", contentType = "searches") {
                                        Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
                                            SectionTitle("最近搜索", "清空") { c.store.update { it.copy(recentSearches = emptyList()) } }
                                            Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { local.recentSearches.take(8).forEach { value -> AppActionChip(onClick = { query = value; search() }, label = { Text(value.take(20)) }) } }
                                        }
                                    }
                                }
                                if(local.savedSearchPresets.isNotEmpty()) item(key = "saved-searches", contentType = "searches") { SectionTitle("保存的搜索", "管理") { searchListOpen = "saved" }; Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { local.savedSearchPresets.forEach { value -> AppActionChip(onClick = { applyPreset(value) }, label = { Text(value.name.take(20)) }) } } }
                                item(key = "web-heading", contentType = "heading") { SectionTitle("热门网络小说", "更多") { category = 1; sort = 1 } }
                                items(visibleWeb, key = { "web-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)), showReadingProgress = false) }
                                item(key = "wenku-heading", contentType = "heading") { SectionTitle("文库新近更新", "更多") { category = 2 } }
                                items(visibleWenku, key = { "wenku-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)), showReadingProgress = false) }
                                item(key = "refresh", contentType = "controls") { TextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新推荐") } }
                            }
                        }
                    } else {
                        DiscoverToolbar(
                            filters = if(category == 1) listOf(
                                QuickFilter("排序", listOf("更新", "点击", "相关"), sort) { sort = it; page = 0 },
                                QuickFilter("状态", listOf("全部状态", "连载中", "已完结", "短篇"), type) { type = it; page = 0 },
                            ) else emptyList(),
                            summary = filterSummary, filterCount = filterCount,
                            resultLabel = if(submitted.isBlank()) "浏览全部" else "搜索结果",
                            isSaved = local.savedSearchPresets.any { it.hasSameConditions(currentPreset()) },
                            onFilter = { filterOpen = true }, onAssistant = if(category == 1) ({ assistantOpen = true }) else null,
                            onRecent = { searchListOpen = "recent" }, onSaved = { searchListOpen = "saved" },
                            onSave = { presetToSave = currentPreset() }, onReset = ::resetFilters,
                        )
                        if(category == 1 && keywordPersistenceError != null) Text(keywordPersistenceError!!, Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        val effectiveWebLevel = if(profile?.canEdit == true) webLevel.coerceIn(0, 2) else 1
                        val effectiveWenkuLevel = wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)
                        val requestKey = listOf(category, page, submitted, source, type, translate, sort, effectiveWebLevel,
                            effectiveWenkuLevel, profile?.username, profile?.canEdit, local.blockedAuthors, searchRevision,
                            localFilter, local.blockedBooks, local.blockedTags, cacheGeneration)
                        if(category == 1 && localFilter.active) FilteredNovelList(c, requestKey, localFilter, local,
                            loadPage = { next, refresh -> c.filteredWebList(next, submitted, source, type, effectiveWebLevel, translate, sort, refresh) },
                            onReset = ::resetFilters, keywordLabels = keywordLabels, onPageTurn = onPageTurn)
                        else AsyncContent(requestKey, load = {
                            if(category == 1) c.api.webList(page, submitted, source, type, effectiveWebLevel, translate, sort).let {
                                Page(it.pageNumber, enrichAuthors(it.items.map(WebOutline::card), c, local.blockedAuthors))
                            } else c.api.wenkuList(page, submitted, effectiveWenkuLevel).let {
                                Page(it.pageNumber, enrichAuthors(it.items.map(WenkuOutline::card), c, local.blockedAuthors))
                            }
                        }, initialResult = c.discoverPage?.takeIf { it.first == requestKey }?.second,
                            onLoaded = { c.discoverPage = requestKey to it }, revealContent = true) { result, refresh ->
                            val books = remember(result.items, local.blockedBooks, local.blockedTags, local.blockedAuthors) { result.items.filter { visibleBook(it, local) } }
                            val resultScroll = rememberLazyListState()
                            AppLazyColumn(state = resultScroll, onPageTurn = onPageTurn) {
                                if(books.isEmpty()) item {
                                    EmptyState("没有找到匹配的作品", if(filterCount > 0) "先放宽筛选条件，搜索关键词会保留。" else "试试较短的关键词，或检查屏蔽条件。", Icons.Outlined.SearchOff,
                                        if(filterCount > 0) "放宽筛选" else if(submitted.isNotBlank()) "浏览全部作品" else "重新加载",
                                        { if(filterCount > 0) resetFilters() else if(submitted.isNotBlank()) { query = ""; submitted = ""; page = 0 } else refresh() }, sticker = MidoriSticker.Curious)
                                    if(local.blockedBooks.isNotEmpty() || local.blockedTags.isNotEmpty() || local.blockedAuthors.isNotEmpty()) TextButton(onClick = { c.go("blocked") }, Modifier.fillMaxWidth()) { Text("检查屏蔽条件") }
                                }
                                items(books, key = { it.ref.key }, contentType = { "book" }) {
                                    DiscoverBookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(
                                        fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)),
                                        keywordLabels = keywordLabels)
                                }
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
                WebSourceFilter(source) { source = it; page = 0 }
                ChoiceRow("连载状态", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 }
                ChoiceRow("已有译文", listOf("全部", "GPT", "Sakura"), translate) { translate = it; page = 0 }
                ChoiceRow("排序", listOf("更新", "点击", "相关"), sort) { sort = it; page = 0 }
                if(profile?.canEdit == true) ChoiceRow("分级", listOf("全部", "一般向", "R18"), webLevel.coerceIn(0, 2)) { webLevel = it; page = 0 }
                CharacterCountFilterFields(localFilter.characters) { localFilter = localFilter.copy(characters = it); page = 0 }
                Text("按作品提供的字数筛选；首次查找会补取字数，可继续加载更多结果。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            } else ChoiceRow("文库分类", if(profile?.canEdit == true) listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向") else listOf("全部小说", "轻小说", "轻文学", "文学", "非小说"), wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)) { wenkuLevel = it; page = 0 }
            if(category == 1) FilledTonalButton(onClick = { filterOpen = false; assistantOpen = true }, Modifier.padding(horizontal = 20.dp)) { Text("打开辅助搜索") }
            Text("搜索框支持原站查询表达式。规则与示例可在站内使用教程中查看。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { c.go("article/${ForumLinks.TUTORIAL_ID}"); filterOpen = false }, Modifier.padding(horizontal = 12.dp)) { Text("查看搜索语法") }
            Button(onClick = { filterOpen = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("查看结果") }
        }
    }
    if(assistantOpen) AppSheet(onDismissRequest = { assistantOpen = false }) {
        assistantState.SaveableStateProvider("discover-assistant") {
            SearchAssistantPanel(query, keywords.entries, true, { assistantOpen = it },
                onApply = { expression -> query = expression; search() },
                onSaveTranslation = c.app.keywords::setTranslation,
                onHelp = { assistantOpen = false; c.go("article/${ForumLinks.TUTORIAL_ID}") },
                persistenceError = keywordPersistenceError, categoryNames = keywords.categories,
                libraryActions = rememberKeywordLibraryActions(c.app.keywords), sheetMode = true)
        }
    }
    if(searchListOpen.isNotEmpty()) AppSheet(onDismissRequest = { searchListOpen = "" }) {
        val recent = searchListOpen == "recent"
        val searches = local.recentSearches
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if(recent) "最近搜索" else "保存的搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if(recent && searches.isNotEmpty()) TextButton(onClick = { c.store.update { it.copy(recentSearches = emptyList()) } }) { Text("清空") }
            TextButton(onClick = { searchListOpen = "" }) { Text("关闭") }
        }
        AppScrollColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentModifier = Modifier.padding(bottom = 24.dp)) {
            if(if(recent) searches.isEmpty() else local.savedSearchPresets.isEmpty()) Text(if(recent) "还没有搜索记录" else "还没有保存的搜索", Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(recent) searches.forEach { value ->
                MenuRow(value, "点击搜索", Icons.Outlined.History, { query = value; searchListOpen = ""; search() })
            } else local.savedSearchPresets.forEach { value ->
                MenuRow(value.name, value.summary(), Icons.Outlined.Bookmark,
                    { searchListOpen = ""; applyPreset(value) },
                    trailing = { Row {
                        IconButton(onClick = { presetToSave = value; searchListOpen = "" }) { Icon(Icons.Outlined.Edit, "重命名搜索 ${value.name}") }
                        IconButton(onClick = { c.store.update { state -> state.copy(savedSearchPresets = state.savedSearchPresets.filterNot { it.id == value.id }) } }) { Icon(Icons.Outlined.Close, "删除保存的搜索 ${value.name}") }
                    } })
            }
        }
    }
    presetToSave?.let { preset -> SaveSearchPresetDialog(preset, onDismiss = { presetToSave = null }) { value ->
        c.action {
            check(c.store.recoveryIssue.value == null) { "本地资料处于恢复保护状态，暂时无法保存搜索" }
            c.store.update { state -> state.copy(savedSearchPresets = state.savedSearchPresets.filterNot { it.id == value.id } + value) }
            c.store.flush()
            presetToSave = null
            c.message("已保存搜索组合：${value.name}")
        }
    } }
}

/** 列表缺少作者或字数时，按已启用的本地条件合并补查，复用详情缓存。 */
internal suspend fun enrichAuthors(books: List<BookCard>, c: AppController, blockedAuthors: Set<String>, characters: Boolean = false,
    forceNetwork: Boolean = false): List<BookCard> {
    if(blockedAuthors.isEmpty() && !characters) return books
    val binding = c.session.capture()
    val limit = Semaphore(5)
    val enriched = coroutineScope {
        books.map { book -> async {
            val needsAuthors = blockedAuthors.isNotEmpty() && book.authors.isEmpty()
            val needsCharacters = characters && !book.ref.isWenku && !book.ref.isLocal && (book.totalCharacters == null || forceNetwork)
            if(!needsAuthors && !needsCharacters) book else limit.withPermit {
                try {
                    if(book.ref.isWenku) book.copy(authors = c.detail<WenkuDetail>("wenku/${book.ref.id}").authors)
                    else if(characters) c.filterBookMetadata(book, forceNetwork)
                    else c.detail<WebDetail>("novel/${book.ref.key}").let { detail ->
                        book.copy(authors = detail.authors.map { it.name }, totalCharacters = detail.totalCharacters?.takeIf { it >= 0 } ?: book.totalCharacters)
                    }
                } catch(e: CancellationException) { throw e }
                  catch(_: Exception) { book }
            }
        } }.map { it.await() }
    }
    c.session.ensureCurrent(binding)
    if(characters) {
        val lookup = enriched.associateBy { it.ref.key }
        // 每批一次合并，避免逐本更新整份书架；筛选元数据不冒充已读取完整目录。
        c.store.update { state -> state.copy(books = state.books.map { saved ->
            val found = lookup[saved.book.ref.key]
            if(found == null) saved else saved.copy(book = saved.book.copy(
                totalCharacters = found.totalCharacters ?: saved.book.totalCharacters,
                authors = found.authors.ifEmpty { saved.book.authors },
            ))
        }) }
    }
    return enriched
}
