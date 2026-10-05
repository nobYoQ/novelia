@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.data.model.ForumSort
import cc.novelia.app.data.model.ForumCategory
import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.library.SavedArticleFreshness
import cc.novelia.app.data.model.ForumPage
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.Page
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable

@Composable fun CommunityScreen(c: AppController) {
    AsyncContent("forum-categories", load = { c.forumApi.categories() }) { available, _ ->
        if(available.isEmpty()) EmptyState("论坛还没有分类", "测试站正在准备内容。", Icons.Outlined.Forum)
        else ForumCommunity(c, available)
    }
}
@Composable private fun ForumCommunity(c: AppController, available: List<ForumCategory>) {
    var category by rememberSaveable { mutableStateOf(available.first().slug) }; var page by rememberSaveable { mutableIntStateOf(0) }; var search by rememberSaveable { mutableStateOf("") }; var saved by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(ForumSort.ACTIVE) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var localRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(available) {
        if(available.none { it.slug == category }) { category = available.first().slug; page = 0 }
    }
    val profile by c.forumSession.profile.collectAsStateWithLifecycle()
    val forumBinding = c.forumSession.capture()
    var hasUnreadStrikes by remember(forumBinding) { mutableStateOf(false) }
    var attentionRefresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 仅在社区前台检查；菜单展开立即复核。失败保留当前提醒，退出或换号清除。
    LaunchedEffect(forumBinding, profile != null, attentionRefresh, lifecycle) {
        if(profile != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) {
                try { hasUnreadStrikes = c.forumAccountApi.attentionStatus(forumBinding).strikes.hasUnread }
                catch(error: CancellationException) { throw error }
                catch(_: Exception) { }
                delay(60_000)
            }
        }
    }
    val mainProfile by c.session.profile.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    var draftBoxOpen by rememberSaveable { mutableStateOf(false) }
    fun showFeed(value: Int, local: Boolean = false) { source = value; saved = local; page = 0 }
    Screen("社区", actions = {
        IconButton(onClick = { draftBoxOpen = true }) { Icon(Icons.Outlined.Drafts, "新帖草稿箱") }
        IconButton(onClick = { c.go("compose") }) { Icon(Icons.Outlined.Edit, "新建帖子草稿") }
        ForumAccountMenu(profile, hasUnreadStrikes, onOpen = { attentionRefresh++ }) { action ->
            when(action) {
                ForumAccountAction.LOGIN -> c.go("forum-login")
                ForumAccountAction.POSTS -> c.requireForumLogin { showFeed(2) }
                ForumAccountAction.FAVORITES -> c.requireForumLogin { showFeed(1) }
                ForumAccountAction.LOCAL -> showFeed(0, local = true)
                ForumAccountAction.STRIKES -> c.requireForumLogin { c.go("forum-strikes") }
                ForumAccountAction.RULES -> c.go("forum-rules")
                ForumAccountAction.LOGOUT -> c.action("已退出论坛登录") { c.forumSession.logout(); showFeed(0) }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ForumRulesReminder(c)
            ForumCategoryTabs(available, category) { category = it; page = 0; saved = false; source = 0 }
            ForumFeedControls(if(saved) "本地收藏" else when(source) { 1 -> "云端收藏"; 2 -> "我的帖子"; else -> "全部帖子" },
                search, searchExpanded,
                if(saved) "搜索本地收藏" else if(source == 0) "搜索论坛帖子" else "在本页文章中查找",
                onExpanded = { searchExpanded = it }, onSearch = { search = it; page = 0 }) {
                if(source == 0 && !saved) ForumSortPicker(sort) { sort = it; page = 0 }
                else {
                    TextButton(onClick = { showFeed(0) }) { Text("返回全部帖子") }
                    if(saved) IconButton(onClick = { localRefresh++ }) { Icon(Icons.Outlined.Refresh, "刷新本地收藏状态") }
                }
            }
            val settledSearch = rememberDebouncedQuery(search)
            MotionContent(listOf(category, saved, source), Modifier.weight(1f), animateInitial = false) {
                if(saved) {
                    val articles = remember(state.savedArticles, settledSearch) { state.savedArticles.filter { it.title.contains(settledSearch, true) } }
                    val pageCount = ForumPage<Article>(articles.size.toLong(), emptyList()).pageCount().coerceAtLeast(1)
                    val localPage = page.coerceIn(0, pageCount - 1)
                    val snapshots = articles.drop(localPage * 20).take(20)
                    AsyncContent(listOf("local-articles", snapshots.map { it.id }, mainProfile?.userId, mainProfile?.role,
                        profile?.userId, profile?.role), refreshKey = localRefresh,
                        load = { c.refreshSavedArticles(snapshots, available) }) { refreshed, _ ->
                        val current = state.savedArticles.associateBy { it.id }
                        val visible = refreshed.mapNotNull { current[it.article.id] }
                        val freshness = refreshed.associate { item -> item.article.id to
                            if(current[item.article.id] != item.article) SavedArticleFreshness.CURRENT else item.freshness }
                        ArticleList(c, Page(pageCount, visible), localPage, { page = it }, freshness)
                    }
                } else AsyncContent(listOf(category, page, settledSearch, source, sort, profile?.userId), load = {
                    when(source) { 1 -> c.forumApi.favorites(page); 2 -> c.forumApi.myPosts(page); else -> c.forumApi.posts(page, category, settledSearch, sort.apiValue) }
                }) { result, _ ->
                    val articles = remember(result.items, settledSearch, state.blockedUsers, available) { result.items.filter { (source == 0 || it.title.contains(settledSearch, true)) && it.authorUsername !in state.blockedUsers }.map { it.article(available) } }
                    ArticleList(c, Page(result.pageCount(), articles), page, { page = it })
                }
            }
        }
    }
    if(draftBoxOpen) AppSheet(onDismissRequest = { draftBoxOpen = false }) { ArticleDraftBox(c) { draftBoxOpen = false } }
}
@Composable private fun ArticleList(c: AppController, result: Page<Article>, page: Int, changePage: (Int) -> Unit,
    freshness: Map<String, SavedArticleFreshness> = emptyMap()) {
    val reducedMotion = appReducedMotion()
    AppLazyColumn {
        if(result.items.isEmpty()) item { EmptyState("这里暂时没有文章", "试试其他分类，或调整搜索词。", Icons.Outlined.Forum) }
        items(result.items, key = { it.id }, contentType = { "article" }) { article ->
            val verified = freshness[article.id]?.let { it == SavedArticleFreshness.CURRENT } ?: true
            Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
                Column(Modifier.fillMaxWidth().motionClickable { c.go("article/${article.id}") }.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { if(verified && article.pinned) Icon(Icons.Outlined.PushPin, "置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Text(categories[article.category] ?: article.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium); if(verified && article.locked) Icon(Icons.Outlined.Lock, "已锁定", Modifier.size(14.dp)); if(verified && article.hidden) Text("已隐藏", style = MaterialTheme.typography.labelMedium) }
                    Text(article.title, style = MaterialTheme.typography.titleMedium)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${article.user.username} · ${displayDate(article.createAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if(verified) Text("${article.numComments} 评论 · ${article.numViews} 查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(!verified) Text(if(freshness[article.id] == SavedArticleFreshness.UNAVAILABLE) "已删除或不可访问" else "状态未核实 · 显示本地缓存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(article.id in freshness) TextButton(onClick = { c.store.update { it.copy(savedArticles = it.savedArticles.filterNot { saved -> saved.id == article.id }) } }) { Text("取消本地收藏") }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { PageControls(page, result.pageNumber, changePage) }
    }
}
