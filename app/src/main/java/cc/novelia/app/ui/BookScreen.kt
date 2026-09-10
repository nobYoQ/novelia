@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun BookScreen(c: AppController, ref: BookRef) {
    var menu by remember { mutableStateOf(false) }; var favorite by remember { mutableStateOf<BookCard?>(null) }; var download by remember { mutableStateOf<Pair<BookCard, String?>?>(null) }; var version by remember { mutableIntStateOf(0) }
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    var progressChoice by remember { mutableStateOf<Triple<BookCard, String, String>?>(null) }
    var uploadBusy by remember { mutableStateOf(false) }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action("分卷上传完成") { uploadBusy = true; try { withContext(Dispatchers.IO) { val (name, bytes) = readDocument(c, it); require(name.substringAfterLast('.').lowercase() in listOf("epub", "txt") && bytes.size <= 40 * 1024 * 1024) { "文库上传支持不超过 40 MB 的 EPUB / TXT" }; val file = File(c.app.cacheDir, "upload-${System.nanoTime()}"); try { file.writeBytes(bytes); c.api.uploadVolume(ref, name, file) } finally { file.delete() } }; version++ } finally { uploadBusy = false } } } }
    Screen(if(ref.isWenku) "文库详情" else "作品详情", c::back, actions = {
        IconButton(onClick = { c.share(ref.url) }) { Icon(Icons.Outlined.Share, "分享作品") }
        Box { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }; DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("刷新资料") }, { version++; menu = false }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
            DropdownMenuItem({ Text("术语表") }, { c.go("glossary/${ref.key}"); menu = false }, leadingIcon = { Icon(Icons.Outlined.Translate, null) })
            DropdownMenuItem({ Text("编辑书籍信息") }, { c.requireLogin { c.go("edit/${ref.key}") }; menu = false }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
            DropdownMenuItem({ Text("在原站打开") }, { c.external(ref.url); menu = false }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) })
            BookLinks.source(ref)?.let { url -> DropdownMenuItem({ Text("打开书源") }, { c.external(url); menu = false }) }
            DropdownMenuItem({ Text("屏蔽这本书") }, { c.store.update { it.copy(blockedBooks = it.blockedBooks + ref.key) }; c.message("已从发现列表中屏蔽"); menu = false }, leadingIcon = { Icon(Icons.Outlined.Block, null) })
        } }
    }) { padding ->
        if(ref.isWenku) AsyncContent(listOf(ref, version, profile?.username), load = { c.detail<WenkuDetail>("wenku/${ref.id}") }, modifier = Modifier.padding(padding)) { detail, refresh ->
            val book = detail.card(ref)
            var tab by rememberSaveable(ref.key) { mutableIntStateOf(0) }
            Column {
                PrimaryTabRow(tab) { listOf("简介", "分卷", "讨论").forEachIndexed { i, title -> Tab(tab == i, { tab = i }, text = { Text(title) }) } }
                when(tab) {
                    0 -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { BookHero(book, "${detail.level} · ${detail.volumes.size} 卷", { favorite = book }) }
                        item { MetaParagraph("简介", detail.introduction) }
                        item { TagList(detail.keywords, c) }
                        item { MetaParagraph("出版信息", listOfNotNull(detail.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "作者："), detail.artists.takeIf { it.isNotEmpty() }?.joinToString(prefix = "插画："), detail.publisher, detail.imprint).joinToString("\n")) }
                        if(detail.webIds.isNotEmpty()) item { SectionTitle("关联网络版"); detail.webIds.forEach { id -> TextButton(onClick = { c.book(BookRef.fromKey(id)) }, Modifier.padding(horizontal = 12.dp)) { Text(id) } } }
                    }
                    1 -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if(profile == null) item { EmptyState("登录后查看文库文件", "文库的资源目录与下载遵循原站权限。", action = "登录", onAction = { c.go("login") }) }
                        if(detail.volumeJp.isNotEmpty()) item { SectionTitle("已有译文的分卷") }
                        items(detail.volumeJp, key = { it.volumeId }) { volume ->
                            val complete = maxOf(volume.sakura, volume.gpt, volume.youdao) >= volume.total && volume.total > 0
                            ListItem(headlineContent = { Text(volume.volumeId) }, supportingContent = { Text("Sakura ${volume.sakura} · GPT ${volume.gpt} · 有道 ${volume.youdao} / ${volume.total}") }, trailingContent = { IconButton(onClick = { download = book to volume.volumeId }, enabled = complete) { Icon(Icons.Outlined.Download, if(complete) "下载分卷" else "译文尚未完成") } })
                        }
                        if(detail.volumeZh.isNotEmpty()) item { SectionTitle("中文文件") }
                        if(profile?.role == "admin") items(detail.volumeZh) { name -> MenuRow(name, "打开原站提供的中文资源", Icons.Outlined.Description, { c.external("https://n.novelia.cc/files-wenku/${ref.id}/${encodeSegment(name)}") }) }
                        if(detail.volumes.isNotEmpty()) item { SectionTitle("出版卷目") }
                        items(detail.volumes, key = { it.asin }) { volume -> BookRow(BookCard(ref, volume.titleZh ?: volume.title, volume.title, volume.cover, volume.publisher.orEmpty()), { volume.coverHires?.let(c::external) }) }
                        if(profile?.canEdit == true) item { OutlinedButton(onClick = { uploader.launch(arrayOf("*/*")) }, enabled = !uploadBusy, modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(if(uploadBusy) "正在上传…" else "上传日文分卷") } }
                        if(detail.volumes.isEmpty() && detail.volumeJp.isEmpty() && profile != null) item { EmptyState("暂时没有可用分卷", "可刷新资料，或在具有编辑权限时上传资源。", action = "刷新", onAction = refresh) }
                    }
                    2 -> CommentsPanel(c, "wenku-${ref.id}")
                }
            }
        } else AsyncContent(listOf(ref, version, profile?.username), load = { c.detail<WebDetail>("novel/${ref.key}") }, modifier = Modifier.padding(padding)) { detail, refresh ->
            val book = detail.card(ref)
            var tab by rememberSaveable(ref.key) { mutableIntStateOf(0) }
            val start = state.positions[ref.key]?.chapterId ?: detail.lastReadChapterId ?: detail.toc.firstOrNull { it.chapterId != null }?.chapterId
            Column {
                PrimaryTabRow(tab) { listOf("简介", "目录 ${detail.toc.count { it.chapterId != null }}", "讨论").forEachIndexed { i, title -> Tab(tab == i, { tab = i }, text = { Text(title) }) } }
                when(tab) {
                    0 -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { BookHero(book, "${detail.type} · ${providers[ref.provider]}", { favorite = book }) }
                        item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { start?.let { val localChapter = state.positions[ref.key]?.chapterId; val cloudChapter = detail.lastReadChapterId; if(localChapter != null && cloudChapter != null && localChapter != cloudChapter) progressChoice = Triple(book, localChapter, cloudChapter) else { c.store.saveBook(book); c.read(ref, it) } } }, enabled = start != null, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(state.positions.containsKey(ref.key) || detail.lastReadChapterId != null) "继续阅读" else "开始阅读") }
                            FilledTonalIconButton(onClick = { download = book to null }) { Icon(Icons.Outlined.Download, "下载小说") }
                        } }
                        item { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("章节", "${book.total}"); Stat("字数", detail.totalCharacters?.let { if(it > 10000) "${it / 10000}万" else "$it" } ?: "—"); Stat("浏览", "${detail.visited}")
                        } }
                        item { MetaParagraph("简介", detail.introductionZh?.takeIf(String::isNotBlank) ?: detail.introductionJp) }
                        item { TagList(detail.keywords + detail.attentions, c) }
                        item { SectionTitle("作者"); detail.authors.forEach { author -> TextButton(onClick = { c.go("discover?query=${android.net.Uri.encode(author.name)}") }, Modifier.padding(horizontal = 12.dp)) { Text(author.name) } } }
                        item { MetaParagraph("译文进度", "原文 ${detail.jp} · Sakura ${detail.sakura} · GPT ${detail.gpt} · 有道 ${detail.youdao}") }
                        detail.wenkuId?.let { id -> item { MenuRow("关联文库版", "查看分卷与出版信息", Icons.Outlined.LibraryBooks, { c.book(BookRef("wenku", id)) }) } }
                        item { TextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新书籍资料") } }
                    }
                    1 -> TocPanel(c, ref, detail.toc, start) { id -> c.store.saveBook(book); c.read(ref, id) }
                    2 -> CommentsPanel(c, "web-${ref.provider}-${ref.id}")
                }
            }
        }
    }
    favorite?.let { FavoriteSheet(c, it) { favorite = null } }
    download?.let { (book, volume) -> DownloadSheet(c, book, volume) { download = null } }
    progressChoice?.let { (book, localChapter, cloudChapter) -> AlertDialog(onDismissRequest = { progressChoice = null }, title = { Text("选择继续阅读的位置") }, text = { Text("本机与原站记录的章节不同，请选择这次从哪里继续。") }, confirmButton = { TextButton(onClick = { progressChoice = null; c.store.saveBook(book); c.read(ref, cloudChapter) }) { Text("原站进度：$cloudChapter") } }, dismissButton = { TextButton(onClick = { progressChoice = null; c.store.saveBook(book); c.read(ref, localChapter) }) { Text("本机进度：$localChapter") } }) }
}
@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun BookHero(book: BookCard, subtitle: String, favorite: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BookCover(book, Modifier.width(94.dp).height(134.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(book.title, style = MaterialTheme.typography.titleLarge); Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(book.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedButton(onClick = favorite, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("收藏到书架") }
    }
}
@Composable fun MetaParagraph(label: String, text: String) { if(text.isNotBlank()) { SectionTitle(label); SelectionContainer { Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge) } } }
@Composable private fun TagList(tags: List<String>, c: AppController) { FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tags.forEach { tag -> SuggestionChip(onClick = { c.go("discover?query=${android.net.Uri.encode(tag)}") }, label = { Text(tag) }) } } }
@Composable fun TocPanel(c: AppController, ref: BookRef, toc: List<TocItem>, current: String?, onRead: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }; var reversed by rememberSaveable { mutableStateOf(false) }
    val list = toc.filter { it.title.contains(search, true) || it.titleJp.contains(search, true) }.let { if(reversed) it.reversed() else it }
    val scroll = rememberLazyListState(); val scope = rememberCoroutineScope()
    Column {
        OutlinedTextField(search, { search = it }, label = { Text("搜索章节") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = { reversed = !reversed }) { Text(if(reversed) "倒序" else "正序") }
            TextButton(onClick = { val index = list.indexOfFirst { it.chapterId == current }; if(index >= 0) scope.launch { scroll.animateScrollToItem(index) } }) { Text("定位当前") }
            if(!ref.isLocal) TextButton(onClick = { c.action("章节已缓存，可离线阅读") { val chapters = list.filter { it.chapterId != null }.take(20); withContext(Dispatchers.IO) { for(item in chapters) { val id = item.chapterId!!; if(c.store.cachedChapter(ref, id) == null) c.store.cacheChapter(ref, id, c.api.chapter(ref, id)) } } } }) { Text("缓存前 20 章") }
        }
        LazyColumn(state = scroll) {
            itemsIndexed(list, key = { index, item -> item.chapterId ?: "section-$index" }) { _, item ->
                if(item.chapterId == null) SectionTitle(item.title)
                else ListItem(headlineContent = { Text(item.title, color = if(item.chapterId == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }, leadingContent = { Icon(if(item.chapterId == current) Icons.Outlined.Bookmark else Icons.Outlined.Article, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.fillMaxWidth().clickable { onRead(item.chapterId) })
            }
            if(list.isEmpty()) item { EmptyState("没有匹配的章节", "可以修改搜索词，或刷新书籍目录。") }
        }
    }
}
