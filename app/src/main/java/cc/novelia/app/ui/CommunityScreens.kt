@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import kotlinx.serialization.encodeToString

val categories = linkedMapOf("General" to "小说交流", "Guide" to "使用指南", "Support" to "反馈建议")
@Composable fun CommunityScreen(c: AppController) {
    var category by rememberSaveable { mutableStateOf("General") }; var page by rememberSaveable { mutableIntStateOf(0) }; var search by rememberSaveable { mutableStateOf("") }; var saved by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    val state by c.store.state.collectAsStateWithLifecycle()
    Screen("社区", actions = {
        IconToggleButton(checked = saved, onCheckedChange = { saved = it }) {
            Crossfade(saved, animationSpec = tween(if(reducedMotion) 0 else 160), label = "savedArticles") { showingSaved ->
                Icon(if(showingSaved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "已收藏的文章")
            }
        }
        IconButton(onClick = { c.requireLogin { c.go("compose") } }) { Icon(Icons.Outlined.Edit, "发布帖子") }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(categories.keys.indexOf(category)) { categories.forEach { (key, label) -> Tab(category == key, { category = key; page = 0; saved = false }, text = { Text(label) }) } }
            OutlinedTextField(search, { search = it }, label = { Text(if(saved) "搜索已收藏的文章" else "在本页文章中查找") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), shape = MaterialTheme.shapes.extraLarge)
            val settledSearch = rememberDebouncedQuery(search)
            MotionContent(listOf(category, saved), Modifier.weight(1f), animateInitial = false) {
                if(saved) {
                    val articles = remember(state.savedArticles, settledSearch) { state.savedArticles.filter { it.title.contains(settledSearch, true) } }
                    ArticleList(c, Page(1, articles), 0, {})
                } else AsyncContent(listOf(category, page), load = { c.api.get<Page<Article>>("article", mapOf("page" to "$page", "pageSize" to "20", "category" to category)) }) { result, _ ->
                    val articles = remember(result.items, settledSearch, state.blockedUsers) { result.items.filter { it.title.contains(settledSearch, true) && it.user.username !in state.blockedUsers } }
                    ArticleList(c, result.copy(items = articles), page, { page = it })
                }
            }
        }
    }
}
@Composable private fun ArticleList(c: AppController, result: Page<Article>, page: Int, changePage: (Int) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    AppLazyColumn {
        if(result.items.isEmpty()) item { EmptyState("这里暂时没有文章", "试试其他分类，或调整搜索词。", Icons.Outlined.Forum) }
        items(result.items, key = { it.id }, contentType = { "article" }) { article ->
            Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) {
                Column(Modifier.fillMaxWidth().motionClickable { c.go("article/${article.id}") }.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { if(article.pinned) Icon(Icons.Outlined.PushPin, "置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Text(categories[article.category].orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium); if(article.locked) Icon(Icons.Outlined.Lock, "已锁定", Modifier.size(14.dp)) }
                    Text(article.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${article.user.username} · ${displayDate(article.createAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${article.numComments} 回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { PageControls(page, result.pageNumber, changePage) }
    }
}
@Composable fun ArticleScreen(c: AppController, id: String) {
    val profile by c.session.profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }; var deletion by remember { mutableStateOf(false) }
    val tabState = rememberSaveableStateHolder()
    val reducedMotion = LocalReducedMotion.current
    Screen("文章", c::back, actions = { IconButton(onClick = { c.share("https://n.novelia.cc/forum/$id") }) { Icon(Icons.Outlined.Share, "分享文章") } }) { padding ->
        AsyncContent(id, load = { c.api.get<Article>("article/$id") }, modifier = Modifier.padding(padding)) { article, _ ->
            Column {
                PrimaryTabRow(tab) { listOf("文章内容", "讨论 ${article.numComments}").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
                MotionContent(tab, Modifier.weight(1f), animateInitial = false) {
                    tabState.SaveableStateProvider(tab) {
                        if(tab == 0) {
                        val articleScroll = rememberLazyListState()
                        AppLazyColumn(state = articleScroll, contentPadding = PaddingValues(20.dp)) {
                            item { Text(categories[article.category].orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text(article.title, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("${article.user.username} · ${displayDate(article.createAt)} · ${article.numViews} 次浏览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(24.dp)) }
                            item { MarkdownText(c, article.content, documentUrl = "https://n.novelia.cc/forum/$id",
                                onAnchorScroll = { top -> articleScroll.scrollToItem(1, top) }) }
                            item { Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val saved = state.savedArticles.any { it.id == id }
                                OutlinedButton(onClick = { c.store.update { it.copy(savedArticles = if(saved) it.savedArticles.filterNot { a -> a.id == id } else it.savedArticles + article.copy(id = id)) } }) {
                                    Crossfade(saved, animationSpec = tween(if(reducedMotion) 0 else 160), label = "articleBookmark") { isSaved ->
                                        Icon(if(isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(if(saved) "取消收藏" else "收藏文章")
                                }
                                if(profile?.username == article.user.username) { TextButton(onClick = { c.go("compose?article=$id") }) { Text("编辑") }; TextButton(onClick = { deletion = true }) { Text("删除") } }
                            } }
                        }
                        } else CommentsPanel(c, "article-$id", article.locked)
                    }
                }
            }
        }
    }
    if(deletion) ConfirmDialog("删除这篇文章？", "删除后将从原站移除，请确认已保存需要的内容。", { deletion = false }) { c.action("文章已删除") { c.api.request("DELETE", "article/$id"); c.back() } }
}
@Composable fun ComposeArticleScreen(c: AppController, articleId: String?) {
    if(articleId.isNullOrBlank()) ArticleEditor(c, null) else AsyncContent(articleId, { c.api.get<Article>("article/$articleId") }) { article, _ -> ArticleEditor(c, article.copy(id = articleId)) }
}
@Composable internal fun ArticleEditor(c: AppController, article: Article?) {
    val key = "article:${article?.id ?: "new"}"
    val draft = remember(key) { c.store.state.value.drafts[key] }
    val saved = remember(key) { draft?.let { runCatching { appJson.decodeFromString<Map<String, String>>(it) }.getOrNull() } }
    var title by rememberSaveable(key) { mutableStateOf(saved?.get("title") ?: article?.title.orEmpty()) }; var content by rememberSaveable(key) { mutableStateOf(saved?.get("content") ?: article?.content.orEmpty()) }; var category by rememberSaveable(key) { mutableStateOf(saved?.get("category") ?: article?.category ?: "General") }; var preview by rememberSaveable(key) { mutableStateOf(false) }; var sending by remember { mutableStateOf(false) }
    val editorState = rememberSaveableStateHolder()

    val renderer = rememberMarkdownRenderer(c)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(title, content, category) { kotlinx.coroutines.delay(700); c.store.update { it.copy(drafts = it.drafts + (key to appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to category)))) } }
    Screen(if(article == null) "写一篇帖子" else "编辑帖子", c::back, actions = { TextButton(onClick = { focusManager.clearFocus(); preview = !preview }) { Text(if(preview) "编辑" else "预览") } }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
        // Bound the inner text scroll area to the space left above the keyboard, including landscape.
        val editorHeight = maxHeight.coerceIn(1.dp, 420.dp)
        MotionContent(preview, Modifier.fillMaxSize(), animateInitial = false) {
            editorState.SaveableStateProvider(preview) {
                AppScrollColumn(contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(preview) { Text(title, style = MaterialTheme.typography.headlineMedium); MarkdownText(c, content, renderer = renderer, documentUrl = article?.id?.let { "https://n.novelia.cc/forum/$it" }) }
                    else {
                        OutlinedTextField(title, { if(it.length <= 80) title = it }, label = { Text("标题") }, supportingText = { Text("${title.length} / 80") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        ChoiceRow("分类", categories.values.toList(), categories.keys.indexOf(category)) { category = categories.keys.elementAt(it) }
                        MarkdownEditor(content, { content = it }, editorHeight)
                    }
                    Button(onClick = { c.requireLogin { c.action { sending = true; try { val body = mapOf("title" to title.trim(), "content" to content.trim(), "category" to category); val result = if(article == null) c.api.post("article", body) else c.api.put("article/${article.id}", body); c.store.update { it.copy(drafts = it.drafts - key) }; c.back(); c.go("article/${article?.id ?: result.trim().trim('"')}", replaceTop = article != null && c.nav.currentDestination?.route == "article/{id}" && c.nav.currentBackStackEntry?.arguments?.getString("id") == article.id) } finally { sending = false } } } }, enabled = !sending && title.trim().length in 2..80 && content.trim().length in 2..20000, modifier = Modifier.fillMaxWidth()) { Text(if(sending) "正在提交…" else if(article == null) "发布到社区" else "保存修改") }
                }
            }
        }
        }
    }
}
@Composable fun CommentsPanel(c: AppController, site: String, locked: Boolean = false, parent: String? = null) {
    var page by rememberSaveable(site, parent) { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var text by rememberSaveable(site, parent) { mutableStateOf(c.store.state.value.drafts["comment:$site:$parent"].orEmpty()) }; var reply by remember { mutableStateOf<Comment?>(null) }; var sending by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<Comment?>(null) }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val preferences by c.store.state.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    if(!site.startsWith("article-") && preferences.hideNovelComments) { EmptyState("小说评论已隐藏", "可以在阅读与外观设置中重新开启。", Icons.Outlined.CommentsDisabled); return }
    val markdownRenderer = rememberMarkdownRenderer(c)
    val documentUrl = remember(site) { MarkdownLinks.commentDocumentUrl(site) }
    Column(Modifier.fillMaxSize()) {
        AsyncContent(listOf(site, parent, page, profile?.username), refreshKey = version, load = { c.api.get<Page<Comment>>("comment", buildMap { put("site", site); put("page", "$page"); put("pageSize", "20"); parent?.let { put("parentId", it) } }) }, modifier = Modifier.weight(1f)) { result, _ ->
            val comments = remember(result.items, preferences.blockedUsers) { result.items.filter { it.user.username !in preferences.blockedUsers } }
            AppLazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                if(comments.isEmpty()) item { EmptyState("还没有讨论", "读完之后，来分享你的感想吧。", Icons.Outlined.ChatBubbleOutline) }
                items(comments, key = { it.id }, contentType = { "comment" }) { comment ->
                    Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(160), placementSpec = tween(220), fadeOutSpec = tween(120))) {
                        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(comment.user.username, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(displayDate(comment.createAt), style = MaterialTheme.typography.labelSmall) }
                            if(comment.hidden) Text("这条评论已被隐藏", style = MaterialTheme.typography.bodyMedium) else MarkdownText(c, comment.content, renderer = markdownRenderer, documentUrl = documentUrl)
                            Row { if(parent == null) TextButton(onClick = { reply = comment }) { Text(if(comment.numReplies > 0) "${comment.numReplies} 条回复 · 查看/回复" else if(locked) "查看回复" else "回复") }; if(comment.user.username == profile?.username) TextButton(onClick = { deleting = comment }) { Text("删除") } else TextButton(onClick = { c.store.update { it.copy(blockedUsers = it.blockedUsers + comment.user.username) } }) { Text("屏蔽用户") } }
                            val replies = remember(comment.replies, preferences.blockedUsers) { comment.replies.asSequence().filter { it.user.username !in preferences.blockedUsers }.take(2).toList() }
                            replies.forEach { child -> key(child.id) { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(child.user.username, style = MaterialTheme.typography.labelMedium); if(child.hidden) Text("评论已隐藏", style = MaterialTheme.typography.bodyMedium) else MarkdownText(c, child.content, renderer = markdownRenderer, documentUrl = documentUrl) } } } }
                        }
                        HorizontalDivider()
                    }
                }
                item { PageControls(page, result.pageNumber) { page = it } }
            }
        }
        if(locked) Text("此讨论已锁定，暂时不能回复。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        else Box(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            MarkdownCommentInput(text, { text = it; c.store.update { s -> s.copy(drafts = s.drafts + ("comment:$site:$parent" to text)) } }, if(parent == null) "写下评论" else "回复这条评论") {
            FilledIconButton(onClick = { c.requireLogin { c.action { sending = true; try { val body = buildMap { put("site", site); put("content", text.trim()); parent?.let { put("parent", it) } }; c.api.post("comment", body); text = ""; c.store.update { it.copy(drafts = it.drafts - "comment:$site:$parent") }; version++ } finally { sending = false } } } }, enabled = text.isNotBlank() && !sending) { Icon(Icons.Outlined.Send, "发送评论") }
            }
        }
    }
    reply?.let { comment -> AppSheet(onDismissRequest = { reply = null }) { Column(Modifier.fillMaxHeight(.85f)) { Text("回复 ${comment.user.username}", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge); CommentsPanel(c, site, locked, comment.id) } } }
    deleting?.let { comment -> ConfirmDialog("删除评论？", "这条评论将从原站移除。", { deleting = null }) { c.action { c.api.request("DELETE", "comment/${comment.id}"); version++ } } }
}
