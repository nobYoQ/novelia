@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.library.ReadingDestination
import cc.novelia.app.data.library.offlineRangeLabel
import cc.novelia.app.data.library.readingDestination
import cc.novelia.app.data.library.resumeDestination
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.community.NovelCommentsPanel
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookCover
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.SectionTitle
import cc.novelia.app.ui.components.TagList
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.components.readDocument
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.downloads.DownloadSheet
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ChapterCacheDialog
import cc.novelia.app.ui.shelf.FavoriteSheet
import cc.novelia.app.ui.shelf.bookFavoriteState
import cc.novelia.app.ui.shelf.WenkuSiteOrderActions
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

@Composable fun BookScreen(c: AppController, ref: BookRef, onBack: () -> Unit = c::back) {
    if(ref.isLocal) {
        LocalBookDetailScreen(c, ref, onBack)
        return
    }
    var menu by remember { mutableStateOf(false) }; var favorite by remember { mutableStateOf<Pair<BookCard, Boolean>?>(null) }; var download by remember { mutableStateOf<BookCard?>(null) }; var version by remember { mutableIntStateOf(0) }
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val localSaved = remember(state.books, ref) { state.books.any { it.book.ref == ref } }
    val refreshKey = listOf(version, state.syncStatus[profile?.username]?.lastSuccessAt ?: 0L)
    LaunchedEffect(ref, profile?.username) { favorite = null }
    var progressChoice by remember { mutableStateOf<Pair<ReadingDestination, ReadingDestination>?>(null) }
    var uploadBusy by remember { mutableStateOf(false) }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action("分卷上传完成") { uploadBusy = true; try { withContext(Dispatchers.IO) { val (name, bytes) = readDocument(c, it); require(name.substringAfterLast('.').lowercase() in listOf("epub", "txt") && bytes.size <= 40 * 1024 * 1024) { "文库上传支持不超过 40 MB 的 EPUB / TXT" }; val file = File(c.app.cacheDir, "upload-${System.nanoTime()}"); try { file.writeBytes(bytes); c.api.uploadVolume(ref, name, file) } finally { file.delete() } }; version++ } finally { uploadBusy = false } } } }
    Screen(if(ref.isWenku) "文库详情" else "作品详情", onBack, actions = {
        IconButton(onClick = { c.share(ref.url) }) { Icon(Icons.Outlined.Share, "分享作品") }
        Box { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }; AppDropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("刷新资料") }, { version++; menu = false }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
            DropdownMenuItem({ Text("术语表") }, { c.go("glossary/${ref.key}"); menu = false }, leadingIcon = { Icon(Icons.Outlined.Translate, null) })
            DropdownMenuItem({ Text("编辑书籍信息") }, { c.requireLogin { c.go("edit/${ref.key}") }; menu = false }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
            DropdownMenuItem({ Text("在原站打开") }, { c.external(ref.url); menu = false }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) })
            BookLinks.source(ref)?.let { url -> DropdownMenuItem({ Text("打开书源") }, { c.external(url); menu = false }) }
            DropdownMenuItem({ Text("屏蔽这本书") }, { c.store.update { it.copy(blockedBooks = it.blockedBooks + ref.key) }; c.message("已从发现列表中屏蔽"); menu = false }, leadingIcon = { Icon(Icons.Outlined.Block, null) })
        } }
    }) { padding ->
        if(ref.isWenku) AsyncContent(listOf(ref, profile?.username), refreshKey = refreshKey, load = { c.detail<WenkuDetail>("wenku/${ref.id}", forceNetwork = version > 0) }, modifier = Modifier.padding(padding)) { detail, _ ->
            val book = remember(detail, ref) { detail.card(ref) }
            val favoriteState = bookFavoriteState(ref, localSaved, detail.favored, profile?.username, state.pending)
            val refresh: () -> Unit = { version++ }
            var tab by rememberSaveable(ref.key) { mutableIntStateOf(0) }
            val tabState = rememberSaveableStateHolder()
            AdaptiveBookDetail(tab, { tab = it }, listOf("简介", "分卷", "讨论"), tabState) { panel ->
                    when(panel) {
                        0 -> AppLazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            item { BookHero(book, "${detail.level} · ${detail.volumes.size} 卷") {
                                BookFavoriteActions(favoriteState, { favorite = book to false }, { favorite = book to true })
                            } }
                            detail.latestPublishAt?.takeIf { it > 0 }?.let { item { MetaParagraph("最近出版", displayDate(it)) } }
                            item { MetaParagraph("简介", detail.introduction) }
                            item { TagList(detail.keywords, c) }
                            if(state.books.any { it.parentWenkuKey == ref.key }) item { WenkuSiteOrderActions(c, ref.key) }
                            item { MetaParagraph("出版信息", listOfNotNull(detail.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "作者："), detail.artists.takeIf { it.isNotEmpty() }?.joinToString(prefix = "插画："), detail.publisher, detail.imprint).joinToString("\n")) }
                            detail.authors.filter(String::isNotBlank).forEach { author -> item {
                                MenuRow("屏蔽作者：$author", "在发现列表中隐藏这位作者的作品", Icons.Outlined.PersonOff, {
                                    c.store.update { it.copy(blockedAuthors = it.blockedAuthors + author.trim()) }; c.message("已屏蔽作者 $author")
                                })
                            } }
                            if(detail.volumeJp.isNotEmpty() || detail.volumeZh.isNotEmpty()) item { MetaParagraph("译文情况", "中文文件 ${detail.volumeZh.size} 卷 · 日文分卷 ${detail.volumeJp.size} 卷\nSakura ${detail.volumeJp.sumOf { it.sakura }} · GPT ${detail.volumeJp.sumOf { it.gpt }} · 有道 ${detail.volumeJp.sumOf { it.youdao }} / ${detail.volumeJp.sumOf { it.total }}") }
                            if(detail.webIds.isNotEmpty()) item { SectionTitle("关联网络版"); detail.webIds.forEach { id -> TextButton(onClick = { c.book(BookRef.fromKey(id)) }, Modifier.padding(horizontal = 12.dp)) { Text(id) } } }
                        }
                        1 -> WenkuVolumesPanel(c, book, detail, uploadBusy,
                            onUpload = { uploader.launch(arrayOf("*/*")) }, onRefresh = refresh)
                        2 -> NovelCommentsPanel(c, "wenku-${ref.id}")
                    }
            }
        } else AsyncContent(listOf(ref, profile?.username), refreshKey = refreshKey, load = { c.detail<WebDetail>("novel/${ref.key}", forceNetwork = version > 0) }, modifier = Modifier.padding(padding)) { detail, _ ->
            val book = remember(detail, ref, profile?.username) { detail.card(ref, profile?.username) }
            LaunchedEffect(book) {
                c.store.update { it.withCloudReadingMetadata(listOf(book), c.session.profile.value?.username) }
            }
            val favoriteState = bookFavoriteState(ref, localSaved, detail.favored, profile?.username, state.pending)
            val chapterCount = remember(detail.toc) { detail.toc.count { it.chapterId != null } }
            val refresh: () -> Unit = { version++ }
            var tab by rememberSaveable(ref.key) { mutableIntStateOf(0) }
            val tabState = rememberSaveableStateHolder()
            val localDestination = remember(detail.toc, state.positions[ref.key]?.chapterId) { readingDestination(detail.toc, state.positions[ref.key]?.chapterId) }
            val cloudDestination = remember(detail.toc, detail.lastReadChapterId) { readingDestination(detail.toc, detail.lastReadChapterId) }
            val destination = remember(detail.toc, localDestination, cloudDestination) { resumeDestination(detail.toc, localDestination?.chapterId, cloudDestination?.chapterId) }
            val start = destination?.chapterId
            val continuing = localDestination != null || cloudDestination != null
            AdaptiveBookDetail(tab, { tab = it }, listOf("简介", "目录 $chapterCount", "讨论"), tabState) { panel ->
                    when(panel) {
                        0 -> AppLazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { BookHero(book, "${detail.type} · ${providers[ref.provider]}", bottomPadding = 0.dp) }
                            item { BookReadingActions(destination, continuing,
                                favoriteState = favoriteState, onLocalFavorite = { favorite = book to false }, onCloudFavorite = { favorite = book to true },
                                onRead = { start?.let { if(localDestination != null && cloudDestination != null && localDestination.chapterId != cloudDestination.chapterId) progressChoice = localDestination to cloudDestination else c.read(ref, it) } },
                                onDownload = { download = book }) }
                            item { BookUpdateSummary(detail) { id -> c.read(ref, id) } }
                            if(!continuing && (state.positions.containsKey(ref.key) || detail.lastReadChapterId != null)) item { Text("原进度章节已不在目录中，将从第一章开始。", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            item { FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${book.total} 章", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BookCharacterCount(detail.totalCharacters)
                                Text("${detail.visited} 浏览", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { MetaParagraph("简介", detail.introductionZh?.takeIf(String::isNotBlank) ?: detail.introductionJp) }
                            item { TagList(detail.keywords + detail.attentions, c) }
                            item { SectionTitle("作者"); detail.authors.filter { it.name.isNotBlank() }.forEach { author ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { c.go("discover?query=${android.net.Uri.encode(author.name)}") }, Modifier.weight(1f)) { Text(author.name) }
                                    IconButton(onClick = { c.store.update { it.copy(blockedAuthors = it.blockedAuthors + author.name.trim()) }; c.message("已屏蔽作者 ${author.name}") }) {
                                        Icon(Icons.Outlined.PersonOff, "屏蔽作者 ${author.name}")
                                    }
                                }
                            } }
                            item { MetaParagraph("译文进度", "原文 ${detail.jp} · Sakura ${detail.sakura} · GPT ${detail.gpt} · 有道 ${detail.youdao}") }
                            detail.wenkuId?.let { id -> item { MenuRow("关联文库版", "查看分卷与出版信息", Icons.Outlined.LibraryBooks, { c.book(BookRef("wenku", id)) }) } }
                            item { TextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新书籍资料") } }
                        }
                        1 -> TocPanel(c, ref, detail.toc, start) { id -> c.read(ref, id) }
                        2 -> NovelCommentsPanel(c, "web-${ref.provider}-${ref.id}")
                    }
            }
        }
    }
    favorite?.let { (book, cloud) -> FavoriteSheet(c, book, initialCloud = cloud) { favorite = null } }
    download?.let { book -> DownloadSheet(c, book, emptyList()) { download = null } }
    progressChoice?.let { (localChapter, cloudChapter) -> AppAlertDialog(onDismissRequest = { progressChoice = null }, title = { Text("选择继续阅读的位置") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("本机与原站记录的章节不同，请选择这次从哪里继续。"); Text("本机：${localChapter.label}"); Text("原站：${cloudChapter.label}") } }, confirmButton = { TextButton(onClick = { progressChoice = null; c.read(ref, cloudChapter.chapterId) }) { Text("原站进度") } }, dismissButton = { TextButton(onClick = { progressChoice = null; c.read(ref, localChapter.chapterId) }) { Text("本机进度") } }) }
}
@Composable private fun LocalBookDetailScreen(c: AppController, ref: BookRef, onBack: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    Screen("本地书籍", onBack) { padding ->
        AsyncContent(ref, load = { withContext(Dispatchers.IO) { c.store.documentIndex(ref.id) } }, modifier = Modifier.padding(padding)) { document, _ ->
            val book = state.books.firstOrNull { it.book.ref == ref }?.book ?: BookCard(ref, document.name, cover = document.coverImage, total = document.chapters.size)
            val toc = remember(document) { document.chapters.map { TocItem(titleJp = it.title, chapterId = it.id) } }
            val savedPosition = state.positions[ref.key]
            val destination = remember(toc, savedPosition?.chapterId) { resumeDestination(toc, savedPosition?.chapterId, null) }
            var tab by rememberSaveable(ref.key) { mutableIntStateOf(0) }
            AdaptiveBookDetail(tab, { tab = it }, listOf("信息", "目录 ${toc.size}"), rememberSaveableStateHolder()) { panel ->
                if(panel == 0) AppLazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            BookCover(book, Modifier.width(94.dp).height(134.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(book.title, style = MaterialTheme.typography.titleLarge)
                                Text("${document.format.uppercase()} · ${toc.size} 章", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("已保存在此设备，可离线阅读。", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        Button(onClick = { destination?.let { c.read(ref, it.chapterId) } }, enabled = destination != null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(if(savedPosition == null) "开始阅读" else "继续阅读 · 第 ${destination?.number} 章")
                        }
                        destination?.let { Text(it.title, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    item { MenuRow("本地术语表", "维护此文件的专有名词", Icons.Outlined.Translate, { c.go("glossary/${ref.key}") }) }
                } else TocPanel(c, ref, toc, destination?.chapterId) { c.read(ref, it) }
            }
        }
    }
}

/** 旋转或在单栏、双栏间调整尺寸时，保持相同的面板状态保存键。 */
@Composable internal fun AdaptiveBookDetail(
    selected: Int,
    onSelect: (Int) -> Unit,
    titles: List<String>,
    state: SaveableStateHolder,
    modifier: Modifier = Modifier,
    panel: @Composable (Int) -> Unit,
) {
    val latestPanel by rememberUpdatedState(panel)
    // 若两个调用位置各建普通状态提供器，调整尺寸的同一次组合中，
    // 新注册表会先于旧注册表销毁而创建，导致实时状态丢失；
    // 因此移动面板现有的组合，连同滚动位置和输入字段状态一起保留。
    val panels = remember(state, titles.size) {
        List(titles.size) { index -> movableContentOf { state.SaveableStateProvider(index) { latestPanel(index) } } }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if(maxWidth >= 840.dp) Row(Modifier.fillMaxSize().testTag("book-detail-dual-pane")) {
            Box(Modifier.weight(.44f).fillMaxHeight().testTag("book-detail-summary")) { panels[0]() }
            VerticalDivider()
            Column(Modifier.weight(.56f).fillMaxHeight()) {
                val rightPanel = selected.coerceIn(1, titles.lastIndex)
                BookDetailPager(rightPanel, onSelect, titles.drop(1), firstPanel = 1) { panels[it]() }
            }
        } else Column(Modifier.fillMaxSize().testTag("book-detail-single-pane")) {
            BookDetailPager(selected, onSelect, titles) { panels[it]() }
        }
    }
}

@Composable private fun BookDetailPager(
    selected: Int, onSelect: (Int) -> Unit, titles: List<String>, firstPanel: Int = 0,
    panel: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = (selected - firstPanel).coerceIn(titles.indices)) { titles.size }
    val scope = rememberCoroutineScope()
    val reducedMotion = appReducedMotion()
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager, firstPanel) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { select(it + firstPanel) }
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(pager.currentPage) { titles.forEachIndexed { index, title ->
            Tab(pager.currentPage == index, onClick = { select(index + firstPanel); scope.launch {
                if(reducedMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index)
            } }, text = { Text(title) })
        } }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("book-detail-pager"),
            key = { it + firstPanel }) { panel(it + firstPanel) }
    }
}
@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun BookHero(book: BookCard, subtitle: String, bottomPadding: Dp = 20.dp, actions: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BookCover(book, Modifier.width(94.dp).height(134.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(book.title, style = MaterialTheme.typography.titleLarge); Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(book.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions?.invoke()
    }
}
@Composable fun TocPanel(c: AppController, ref: BookRef, toc: List<TocItem>, current: String?, onRead: (String) -> Unit) {
    var search by rememberSaveable(ref.key) { mutableStateOf("") }; var reversed by rememberSaveable(ref.key) { mutableStateOf(false) }
    var cacheDialog by remember(ref.key) { mutableStateOf(false) }
    var cacheRevision by remember(ref.key) { mutableIntStateOf(0) }
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    var cachedIds by remember(ref.key) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(ref, toc, cacheRevision, cacheGeneration) {
        cachedIds = withContext(Dispatchers.IO) { toc.mapNotNull { item -> item.chapterId?.takeIf { ref.isLocal || c.store.chapterFile(ref, it).isFile } }.toSet() }
    }
    val settledSearch = rememberDebouncedQuery(search)
    val indexedToc = remember(toc) { toc.withIndex().toList() }
    val list = remember(indexedToc, settledSearch, reversed) {
        indexedToc.filter { it.value.title.contains(settledSearch, true) || it.value.titleJp.contains(settledSearch, true) }.let { if(reversed) it.reversed() else it }
    }
    val currentIndex = remember(list, current) { if(current == null) -1 else list.indexOfFirst { it.value.chapterId == current } }
    val hasChapters = remember(list) { list.any { it.value.chapterId != null } }
    val reducedMotion = appReducedMotion()
    val scroll = rememberLazyListState(); val scope = rememberCoroutineScope()
    Column {
        OutlinedTextField(search, { search = it }, label = { Text("搜索章节") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { reversed = !reversed }) { Text(if(reversed) "倒序" else "正序") }
            TextButton(onClick = { scope.launch { if(reducedMotion || abs(currentIndex - scroll.firstVisibleItemIndex) > 100) scroll.scrollToItem(currentIndex) else scroll.animateScrollToItem(currentIndex) } }, enabled = currentIndex >= 0) { Text("定位当前") }
            if(!ref.isLocal) TextButton(onClick = { cacheDialog = true }, enabled = hasChapters) { Text("缓存章节") }
        }
        if(!ref.isLocal) Text(remember(toc, cachedIds) { offlineRangeLabel(toc, cachedIds) }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        AppLazyColumn(state = scroll, modifier = Modifier.weight(1f)) {
            items(list, key = { it.value.chapterId?.let { id -> "chapter-$id" } ?: "section-${it.index}" }, contentType = { if(it.value.chapterId == null) "section" else "chapter" }) { entry ->
                val item = entry.value
                val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
                if(item.chapterId == null) Box(itemMotion) { SectionTitle(item.title) }
                else ListItem(headlineContent = { Text(item.title, color = if(item.chapterId == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }, supportingContent = if(!ref.isLocal && item.chapterId in cachedIds) ({ Text("可离线阅读") }) else null, leadingContent = { Icon(if(item.chapterId == current) Icons.Outlined.Bookmark else Icons.Outlined.Article, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = itemMotion.fillMaxWidth().motionClickable { onRead(item.chapterId) })
            }
            if(list.isEmpty()) item { EmptyState("没有匹配的章节", "可以修改搜索词，或刷新书籍目录。") }
        }
    }
    if(cacheDialog) ChapterCacheDialog(c, ref, toc, current, onChanged = { cacheRevision++ }, onDismiss = { cacheDialog = false; cacheRevision++ })
}
