@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable fun DiscoverScreen(c: AppController, initialQuery: String) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var submitted by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var category by rememberSaveable { mutableIntStateOf(if(initialQuery.isNotBlank()) 1 else 0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf("") }; var type by rememberSaveable { mutableIntStateOf(0) }; var translate by rememberSaveable { mutableIntStateOf(0) }; var sort by rememberSaveable { mutableIntStateOf(0) }
    var webLevel by rememberSaveable { mutableIntStateOf(0) }; var wenkuLevel by rememberSaveable { mutableIntStateOf(0) }
    val reducedMotion = LocalReducedMotion.current
    val local by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    fun search() {
        c.store.rememberSearch(query); page = 0
        if(BookLinks.parse(query) != null) c.openLink(query) else { submitted = query.trim(); if(category == 0) category = 1 }
    }
    Screen("发现", actions = { if(category == 2) IconButton(onClick = { c.requireLogin { c.go("wenku-new") } }) { Icon(Icons.Outlined.Add, "新建文库条目") }; IconButton(onClick = { c.go("rank") }) { Icon(Icons.Outlined.Leaderboard, "排行榜") } }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(query, { query = it }, label = { Text("书名、作者或书源链接") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Outlined.ArrowForward, "搜索") } }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = MaterialTheme.shapes.extraLarge)
            PrimaryTabRow(category) { listOf("为你发现", "网络小说", "文库小说").forEachIndexed { i, label -> Tab(category == i, onClick = { category = i; page = 0 }, text = { Text(label) }) } }
            MotionContent(category, Modifier.weight(1f), animateInitial = false) {
                Column(Modifier.fillMaxSize()) {
                    if(category == 0) {
                        AsyncContent(listOf("recommend", profile?.username), load = { coroutineScope { val web = async { c.api.webList(0, sort = 1) }; val wenku = async { c.api.wenkuList(0) }; web.await().items.map { it.card() } to wenku.await().items.map { it.card() } } }) { (web, wenku), refresh ->
                            val visibleWeb = remember(web, local.blockedBooks, local.blockedTags) { web.asSequence().filter { visibleBook(it, local) }.take(8).toList() }
                            val visibleWenku = remember(wenku, local.blockedBooks, local.blockedTags) { wenku.asSequence().filter { visibleBook(it, local) }.take(6).toList() }
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
                                if(local.recentSearches.isNotEmpty()) item(key = "recent-searches", contentType = "searches") {
                                    Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) {
                                        SectionTitle("最近搜索", "清空") { c.store.update { it.copy(recentSearches = emptyList()) } }
                                        Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { local.recentSearches.take(8).forEach { value -> AssistChip(onClick = { query = value; search() }, label = { Text(value.take(20)) }) } }
                                    }
                                }
                                if(local.savedSearches.isNotEmpty()) item(key = "saved-searches", contentType = "searches") { SectionTitle("保存的搜索"); Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { local.savedSearches.forEach { value -> InputChip(true, onClick = { query = value; search() }, label = { Text(value.take(20)) }, trailingIcon = { IconButton(onClick = { c.store.update { it.copy(savedSearches = it.savedSearches - value) } }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "删除搜索", Modifier.size(16.dp)) } }) } } }
                                item(key = "web-heading", contentType = "heading") { SectionTitle("热门网络小说", "更多") { category = 1; sort = 1 } }
                                items(visibleWeb, key = { "web-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) }
                                item(key = "wenku-heading", contentType = "heading") { SectionTitle("文库新近更新", "更多") { category = 2 } }
                                items(visibleWenku, key = { "wenku-${it.ref.key}" }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) }
                                item(key = "refresh", contentType = "controls") { TextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新推荐") } }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if(submitted.isBlank()) "浏览全部" else "搜索：$submitted", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { if(submitted.isNotBlank()) { c.store.update { it.copy(savedSearches = (it.savedSearches + submitted).distinct()) }; c.message("已保存搜索条件") } }, enabled = submitted.isNotBlank()) {
                                Crossfade(submitted.isNotBlank() && submitted in local.savedSearches, animationSpec = tween(if(reducedMotion) 0 else 160), label = "savedSearch") { isSaved ->
                                    Icon(if(isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, if(isSaved) "搜索已保存" else "保存搜索")
                                }
                            }
                            TextButton(onClick = { filterOpen = true }) { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Text(" 筛选") }
                        }
                        val effectiveWebLevel = if(profile?.canEdit == true) webLevel.coerceIn(0, 2) else 1
                        val effectiveWenkuLevel = wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)
                        AsyncContent(listOf(category, page, submitted, source, type, translate, sort, effectiveWebLevel, effectiveWenkuLevel, profile?.username, profile?.canEdit), load = {
                            if(filterOpen) delay(180)
                            if(category == 1) c.api.webList(page, submitted, source, type, effectiveWebLevel, translate, sort).let { Page(it.pageNumber, it.items.map(WebOutline::card)) }
                            else c.api.wenkuList(page, submitted, effectiveWenkuLevel).let { Page(it.pageNumber, it.items.map(WenkuOutline::card)) }
                        }) { result, refresh ->
                            val books = remember(result.items, local.blockedBooks, local.blockedTags) { result.items.filter { visibleBook(it, local) } }
                            AppLazyColumn {
                                if(books.isEmpty()) item { EmptyState("没有找到匹配的作品", "试试其他关键词，或调整筛选与屏蔽条件。", Icons.Outlined.SearchOff, "重新加载", refresh, sticker = MidoriSticker.Curious) }
                                items(books, key = { it.ref.key }, contentType = { "book" }) { BookRow(it, { c.book(it.ref) }, if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) }
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
            if(category == 1) {
                val selectedSources = remember(source) { source.split(',').filter(String::isNotEmpty).toSet() }
                Text("书源（可多选）", Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { providers.forEach { (id, title) -> FilterChip(id in selectedSources, onClick = { source = selectedSources.toMutableSet().apply { if(!add(id)) remove(id) }.joinToString(","); page = 0 }, label = { Text(title) }) } }
                ChoiceRow("连载状态", listOf("全部", "连载中", "已完结", "短篇"), type) { type = it; page = 0 }
                ChoiceRow("已有译文", listOf("全部", "GPT", "Sakura"), translate) { translate = it; page = 0 }
                ChoiceRow("排序", listOf("更新", "点击", "相关"), sort) { sort = it; page = 0 }
                if(profile?.canEdit == true) ChoiceRow("分级", listOf("全部", "一般向", "R18"), webLevel.coerceIn(0, 2)) { webLevel = it; page = 0 }
            } else ChoiceRow("文库分类", if(profile?.canEdit == true) listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向") else listOf("全部小说", "轻小说", "轻文学", "文学", "非小说"), wenkuLevel.coerceIn(0, if(profile?.canEdit == true) 6 else 4)) { wenkuLevel = it; page = 0 }
            Text("高级搜索", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(onClick = { filterOpen = false; advanced = true }, Modifier.padding(horizontal = 20.dp)) { Text("构建查询条件") }
            Text("搜索框支持原站查询表达式。规则与示例可在站内使用教程中查看。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { c.go("article/64f3d63f794cbb1321145c07"); filterOpen = false }, Modifier.padding(horizontal = 12.dp)) { Text("查看搜索语法") }
            Button(onClick = { filterOpen = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("查看结果") }
        }
    }
    if(advanced) AdvancedSearchSheet(query, { advanced = false }) { expression -> query = expression; advanced = false; search() }
}
@Composable private fun AdvancedSearchSheet(initial: String, dismiss: () -> Unit, apply: (String) -> Unit) {
    var all by remember { mutableStateOf("") }; var any by remember { mutableStateOf("") }; var exact by remember { mutableStateOf("") }; var excluded by remember { mutableStateOf("") }; var tags by remember { mutableStateOf("") }; var excludedTags by remember { mutableStateOf("") }; var minimum by remember { mutableStateOf("") }; var maximum by remember { mutableStateOf("") }
    val expression = SearchExpression.build(all, any, exact, excluded, tags, excludedTags, minimum, maximum)
    AppSheet(onDismissRequest = dismiss) { AppScrollColumn(contentModifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("高级搜索", style = MaterialTheme.typography.titleLarge)
        Text("多个词用空格分隔。生成的表达式可以继续在搜索框中编辑。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(all, { all = it }, label = { Text("全部包含（AND）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(any, { any = it }, label = { Text("任意包含（OR）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exact, { exact = it }, label = { Text("精确短语") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(excluded, { excluded = it }, label = { Text("排除关键词") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tags, { tags = it }, label = { Text("包含标签") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(excludedTags, { excludedTags = it }, label = { Text("排除标签") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数大于") }, modifier = Modifier.weight(1f)); OutlinedTextField(maximum, { maximum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数小于") }, modifier = Modifier.weight(1f)) }
        Text(expression.ifBlank { "填写条件后在此预览表达式" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Button(onClick = { apply(expression) }, enabled = expression.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("应用并搜索") }
    } }
}
fun visibleBook(book: BookCard, state: LibraryState) = book.ref.key !in state.blockedBooks && book.tags.none { it in state.blockedTags }
@Composable fun PageControls(page: Int, count: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(page - 1) }, enabled = page > 0) { Text("上一页") }
        MotionContent(page, animateInitial = false) { Text("${page + 1} / ${count.coerceAtLeast(1)}", style = MaterialTheme.typography.labelLarge) }
        OutlinedButton(onClick = { onChange(page + 1) }, enabled = page + 1 < count) { Text("下一页") }
    }
}

private val kakuyomuGenres = listOf("综合", "异世界幻想", "现代幻想", "科幻", "恋爱", "浪漫喜剧", "现代戏剧", "恐怖", "推理", "散文·纪实", "历史·时代·传奇", "创作论·评论", "诗·童话·其他")
private val syosetuGenres = listOf("恋爱：异世界", "恋爱：现实世界", "幻想：高幻想", "幻想：低幻想", "文学：纯文学", "文学：人性剧", "文学：历史", "文学：推理", "文学：恐怖", "文学：动作", "文学：喜剧", "科幻：VR游戏", "科幻：宇宙", "科幻：空想科学", "科幻：惊悚", "其他：童话", "其他：诗", "其他：散文", "其他：其他")
@Composable fun RankScreen(c: AppController) {
    var source by rememberSaveable { mutableIntStateOf(0) }; var kind by rememberSaveable { mutableIntStateOf(1) }; var genre by rememberSaveable { mutableIntStateOf(0) }; var range by rememberSaveable { mutableIntStateOf(0) }; var status by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableIntStateOf(0) }; var filters by remember { mutableStateOf(false) }
    val provider = if(source == 0) "syosetu" else "kakuyomu"
    val ranges = if(source == 0) listOf("总计", "每年", "季度", "每月", "每周", "每日") else listOf("总计", "每年", "每月", "每周", "每日")
    val states = if(source == 0) listOf("全部", "短篇", "连载", "完结") else listOf("全部", "长篇", "短篇")
    val genres = if(source == 1) kakuyomuGenres else if(kind == 2) listOf("恋爱", "幻想", "文学/科幻/其他") else syosetuGenres
    val params = buildMap { put("range", ranges[range]); put("status", states[status]); if(source == 0) { put("type", listOf("流派", "综合", "异世界转生/转移")[kind]); put("page", "${page + 1}") }; if(source == 1 || kind != 1) put("genre", genres[genre]) }
    Screen("排行榜", c::back, actions = { IconButton(onClick = { filters = true }) { Icon(Icons.Outlined.Tune, "榜单条件") } }) { padding ->
        Column(Modifier.padding(padding)) {
            ChoiceRow("平台", listOf("成为小说家吧", "Kakuyomu"), source) { source = it; range = 0; genre = 0; status = 0; page = 0 }
            MotionContent(listOf(source, kind, genre, range, status), animateInitial = false) { Text("${params["type"] ?: genres[genre]} · ${ranges[range]} · ${states[status]}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            AsyncContent(listOf(provider, params), load = { c.api.get<Page<WebOutline>>("novel/rank/$provider", params) }) { result, _ ->
                val cards = remember(result.items) { result.items.map(WebOutline::card) }
                AppLazyColumn { if(cards.isEmpty()) item { EmptyState("这个榜单暂时没有作品", "可以切换周期或流派；榜单数据由原站获取。") }; items(cards, key = { it.ref.key }, contentType = { "book" }) { book -> BookRow(book, { c.book(book.ref) }) }; item { PageControls(page, result.pageNumber) { page = it } } }
            }
        }
    }
    if(filters) AppSheet(onDismissRequest = { filters = false }) { AppScrollColumn(contentModifier = Modifier.padding(bottom = 24.dp)) {
        if(source == 0) ChoiceRow("榜单", listOf("流派", "综合", "异世界转生/转移"), kind) { kind = it; genre = 0; page = 0 }
        if(source == 1 || kind != 1) ChoiceRow("流派", genres, genre) { genre = it; page = 0 }
        ChoiceRow("周期", ranges, range) { range = it; page = 0 }; ChoiceRow("状态", states, status) { status = it; page = 0 }
        Button(onClick = { filters = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("查看榜单") }
    } }
}
