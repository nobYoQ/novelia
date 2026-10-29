@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.data.community.ForumSort
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.auth.SessionChangedException
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import cc.novelia.app.data.community.Article
import cc.novelia.app.data.model.Page
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.CachedAsyncContent
import cc.novelia.app.ui.components.base.rememberCachedListState
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.FilterPanelVisibility
import cc.novelia.app.ui.components.base.PageControls
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.displayDate
import cc.novelia.app.ui.components.base.rememberDebouncedQuery
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.ObserveForumLogin
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable

@Composable fun CommunityScreen(c: AppController) {
    ObserveForumLogin(c.session, c.forumSession)
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val source = c.forumSession.capture()
    val categoriesKey = listOf("forum-categories", source.source, source.sourceRevision, cacheGeneration)
    // 分类是辅助数据，缓存更新时只由帖子列表显示刷新圆圈，避免重复提示。
    CachedAsyncContent(c.uiResults.categories, categoriesKey, showRefreshIndicator = false, load = {
        // 分类是公开元数据。初次加载碰到自动登录时重取一次，保留已显示的社区导航状态。
        try { c.forumApi.categories() }
        catch(_: SessionChangedException) { c.forumApi.categories() }
    }) { available, _ ->
        if(available.isEmpty()) EmptyState("论坛还没有分类", "测试站正在准备内容。", Icons.Outlined.Forum)
        else ForumCommunity(c, available)
    }
}
@Composable private fun ForumCommunity(c: AppController, available: List<ForumCategory>) {
    var category by rememberSaveable { mutableStateOf(available.first().slug) }; var search by rememberSaveable { mutableStateOf("") }
    var pageNumbers by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    var tagIds by rememberSaveable { mutableStateOf(mapOf<String, Long>()) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    val pageKey = "$category:$source"
    fun setPage(value: Int) { pageNumbers = pageNumbers + (pageKey to value) }
    var sort by rememberSaveable { mutableStateOf(ForumSort.ACTIVE) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var tagsExpanded by rememberSaveable { mutableStateOf(false) }
    val tagId = tagIds[category]
    fun setTag(value: Long?) { tagIds = if(value == null) tagIds - category else tagIds + (category to value) }
    val availableTags = available.firstOrNull { it.slug == category }?.tags.orEmpty()
    val selectedTagId = tagId?.takeIf { id -> availableTags.any { it.id == id } }
    LaunchedEffect(availableTags) {
        if(tagId != null && availableTags.none { it.id == tagId }) { setTag(null); setPage(0) }
    }
    LaunchedEffect(available) {
        if(available.none { it.slug == category }) { category = available.first().slug }
    }
    val profile by c.forumSession.profile.collectAsStateWithLifecycle()
    val forumBinding = c.forumSession.capture()
    val blacklistRevision by c.app.forumBlacklistRevision.collectAsStateWithLifecycle()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
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
    val state by c.store.state.collectAsStateWithLifecycle()
    var draftBoxOpen by rememberSaveable { mutableStateOf(false) }
    fun showFeed(value: Int) { source = value }
    Screen("社区", actions = {
        AppIconButton(onClick = { draftBoxOpen = true }) { Icon(Icons.Outlined.Drafts, "帖子草稿箱") }
        AppIconButton(onClick = { c.go("compose") }) { Icon(Icons.Outlined.Edit, "新建帖子草稿") }
        ForumAccountMenu(profile, hasUnreadStrikes, onOpen = { attentionRefresh++ }) { action ->
            when(action) {
                ForumAccountAction.LOGIN -> c.go("forum-login")
                ForumAccountAction.POSTS -> c.requireForumLogin { showFeed(2) }
                ForumAccountAction.FAVORITES -> c.requireForumLogin { showFeed(1) }
                ForumAccountAction.BLACKLIST -> c.requireForumLogin { c.go("forum-blacklist") }
                ForumAccountAction.STRIKES -> c.requireForumLogin { c.go("forum-strikes") }
                ForumAccountAction.RULES -> c.go("forum-rules")
                ForumAccountAction.LOGOUT -> c.action("已退出论坛登录") { c.forumSession.logout(); showFeed(0) }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ForumRulesReminder(c)
            ForumCategoryPager(available, category, { next ->
                category = next; source = 0
            }, Modifier.weight(1f), controls = {
                ForumFeedControls(when(source) { 1 -> "云端收藏"; 2 -> "我的帖子"; else -> "全部帖子" },
                    search, searchExpanded,
                    if(source == 0) "搜索论坛帖子" else "在本页文章中查找",
                    onExpanded = { searchExpanded = it }, onSearch = { search = it; pageNumbers = emptyMap() }) {
                    if(source == 0 && availableTags.isNotEmpty()) ForumTagFilterToggle(tagsExpanded,
                        availableTags.firstOrNull { it.id == selectedTagId }?.name) { tagsExpanded = !tagsExpanded }
                    if(source == 0) ForumSortPicker(sort) { sort = it; pageNumbers = emptyMap() }
                    else AppTextButton(onClick = { showFeed(0) }) { Text("返回全部帖子") }
                }
                FilterPanelVisibility(source == 0 && tagsExpanded) {
                    ForumTagFilter(availableTags, selectedTagId) { setTag(it); setPage(0) }
                }
            }) { pageCategory ->
                // 邻页使用自身保存的浏览条件，切回分类后继续上次阅读。
                val active = pageCategory.slug == category
                val feedSource = if(active) source else 0
                val feedPage = pageNumbers["${pageCategory.slug}:$feedSource"] ?: 0
                val feedTagId = tagIds[pageCategory.slug]?.takeIf { id -> pageCategory.tags.any { it.id == id } }
                val settledSearch = rememberDebouncedQuery(search)
                val requestKey = listOf(pageCategory.slug, feedPage, settledSearch, feedSource, sort, feedTagId,
                    forumBinding, profile?.role, blacklistRevision, cacheGeneration)
                val scroll = rememberCachedListState(c.forumPages, requestKey)
                CachedAsyncContent(c.forumPages, requestKey, load = {
                    when(feedSource) { 1 -> c.forumApi.favorites(feedPage); 2 -> c.forumApi.myPosts(feedPage); else -> c.forumApi.posts(feedPage, pageCategory.slug, settledSearch, sort.visibleSort.apiValue, listOfNotNull(feedTagId)) }
                }, validate = { c.forumSession.ensureCurrent(forumBinding) }, onLoaded = {
                    // 顶部刷新后显示新插入的帖子，不让旧首项的稳定 key 把视口推到下方。
                    if(!scroll.canScrollBackward) scroll.requestScrollToItem(0)
                },
                    revealContent = true, pullToRefreshEnabled = active) { result, refresh ->
                    val articles = remember(result.items, settledSearch, feedSource, feedTagId, state.blockedUsers, available) {
                        result.items.filter { (feedSource == 0 || it.title.contains(settledSearch, true)) && !it.authorBlocked && it.authorUsername !in state.blockedUsers }
                            .map { it.article(available, if(feedSource == 0) feedTagId else null) }
                    }
                    ArticleList(c, Page(result.pageCount(), articles), feedPage, { if(active) setPage(it) }, scroll, refresh)
                }
            }
        }
    }
    if(draftBoxOpen) AppSheet(onDismissRequest = { draftBoxOpen = false }) { ArticleDraftBox(c) { draftBoxOpen = false } }
}
@Composable private fun ArticleList(c: AppController, result: Page<Article>, page: Int, changePage: (Int) -> Unit,
    scroll: LazyListState, refresh: () -> Unit) {
    val reducedMotion = appReducedMotion()
    AppLazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), listModifier = Modifier.testTag("forum-article-list")) {
        if(result.items.isEmpty()) item { EmptyState("这里暂时没有文章", "试试其他分类，或调整搜索词。", Icons.Outlined.Forum) }
        items(result.items, key = { it.id }, contentType = { "article" }) { article ->
            Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
                Column(Modifier.fillMaxWidth().motionClickable { c.go("article/${article.id}") }.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { if(article.pinned) Icon(Icons.Outlined.PushPin, "置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Text(article.category, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium); if(article.locked) Icon(Icons.Outlined.Lock, "已锁定", Modifier.size(14.dp)); if(article.hidden) Text("已隐藏", style = MaterialTheme.typography.labelMedium) }
                    Text(article.title, style = MaterialTheme.typography.titleMedium)
                    ForumTagBadges(article.forumTags)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${article.user.username} · ${displayDate(article.updateAt.takeIf { it > 0 } ?: article.createAt)}${if(article.updateAt > article.createAt) " 更新" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${article.numComments} 评论 · ${article.numViews} 查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { PageControls(page, result.pageNumber, changePage) }
        item { AppTextButton(onClick = refresh, Modifier.fillMaxWidth()) { Text("刷新帖子") } }
    }
}
