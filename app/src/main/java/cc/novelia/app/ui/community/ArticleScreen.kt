@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.data.community.ForumLinks
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import cc.novelia.app.data.community.Article
import cc.novelia.app.data.community.ForumRules
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.CachedAsyncContent
import cc.novelia.app.ui.components.base.ContentShareButton
import cc.novelia.app.sharing.ShareCard
import cc.novelia.app.sharing.CardKind
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.displayDate
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.ObserveForumLogin
import cc.novelia.app.ui.navigation.FORUM_FAVORITE_REQUEST
import cc.novelia.app.ui.navigation.ForumFavoriteRequest
import cc.novelia.app.ui.navigation.loginForForumFavorite
import kotlinx.coroutines.CancellationException

@Composable fun ArticleScreen(c: AppController, id: String, navigationState: SavedStateHandle? = null) {
    val forumId = requireNotNull(ForumLinks.postId(id))
    ObserveForumLogin(c.session, c.forumSession)
    val session = c.forumSession
    val profile by session.profile.collectAsStateWithLifecycle()
    val binding = session.capture()
    val blacklistRevision by c.app.forumBlacklistRevision.collectAsStateWithLifecycle()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val pendingFavoriteText = navigationState?.getStateFlow<String?>(FORUM_FAVORITE_REQUEST, null)?.collectAsStateWithLifecycle()?.value
    val pendingFavorite = remember(pendingFavoriteText) {
        pendingFavoriteText?.let { runCatching { appJson.decodeFromString<ForumFavoriteRequest>(it) }.getOrNull() }
    }
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }; var deletion by remember(id) { mutableStateOf<Article?>(null) }
    val tabState = rememberSaveableStateHolder()
    val detailKey = listOf(id, binding, profile?.userId, profile?.role, cacheGeneration)
    var shareCard by remember(detailKey) { mutableStateOf<ShareCard?>(null) }
    Screen("文章", c::back, actions = { ContentShareButton(ForumLinks.articleUrl(id), shareCard, "分享文章", c::share) }) { padding ->
        CachedAsyncContent(c.uiResults.articles, detailKey,
            load = { c.article(id) }, modifier = Modifier.padding(padding), revealContent = true,
            observeInvalidation = false, validate = { session.ensureCurrent(binding) }) { article, _ ->
            DisposableEffect(detailKey, article) {
                shareCard = ShareCard(CardKind.Post, article.title, article.content,
                    listOf(article.user.username, displayDate(article.createAt)).filter(String::isNotBlank).joinToString(" · "),
                    listOf("论坛", article.category).filter(String::isNotBlank).joinToString("  /  "),
                    ForumLinks.articleUrl(id), "扫码看原帖", article.forumTags.map { it.name })
                onDispose { shareCard = null }
            }
            val now = rememberForumModificationTime(article.createAt, ForumRules.POST_DELETE_SECONDS)
            val favorite = remember(article, binding) { ForumFavoriteState(article.forumFavorited) }
            // 留在文章层，切换讨论标签或列表回收按钮后仍保留操作结果。
            var authorBlocked by remember(article, binding) { mutableStateOf(article.forumAuthorBlocked) }
            val loadedBlacklistRevision = remember(article, binding) { blacklistRevision }
            LaunchedEffect(article, binding, blacklistRevision) {
                if(profile != null && blacklistRevision != loadedBlacklistRevision) {
                    try {
                        // 评论中也可拉黑作者；读取名单同步按钮，避免重读详情增加浏览计数。
                        authorBlocked = c.forumApi.blacklist.list(binding).items.any { it.userId == article.forumAuthorId }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: SessionChangedException) { /* 新会话会重新载入文章。 */ }
                    catch(error: Exception) { c.message("无法刷新拉黑状态：${error.friendlyMessage()}") }
                }
            }
            fun saveFavorite(value: Boolean) {
                if(favorite.saving || favorite.saved == value) return
                c.action(if(value) "已收藏到云端" else "已取消收藏") {
                    c.forumSession.ensureCurrent(binding)
                    favorite.setSaved(value) { c.forumApi.favorite(forumId, it) }
                }
            }
            LaunchedEffect(pendingFavoriteText, binding) {
                if(pendingFavoriteText != null) {
                    navigationState?.set<String?>(FORUM_FAVORITE_REQUEST, null)
                    if(pendingFavorite?.matches(forumId, binding) == true) saveFavorite(true)
                }
            }
            Column {
                ForumRulesReminder(c)
                ArticleContentPager(tab, { tab = it }, article.numComments, tabState, Modifier.weight(1f)) { panel ->
                    if(panel == 0) {
                        val articleScroll = rememberLazyListState()
                        AppLazyColumn(state = articleScroll, contentPadding = PaddingValues(20.dp)) {
                            item {
                                Text(article.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(article.title, style = MaterialTheme.typography.headlineMedium)
                                Spacer(Modifier.height(12.dp))
                                Text("${article.user.username} · ${displayDate(article.createAt)} · ${article.numViews} 次浏览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if(article.forumTags.isNotEmpty()) { Spacer(Modifier.height(12.dp)); ForumTagBadges(article.forumTags) }
                                Spacer(Modifier.height(24.dp))
                            }
                            item { MarkdownText(c, article.content, documentUrl = ForumLinks.articleUrl(id),
                                onAnchorScroll = { top -> articleScroll.scrollToItem(1, top) }) }
                            item { FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ForumFavoriteButton(favorite) {
                                    if(profile == null) loginForForumFavorite(c, forumId) else saveFavorite(!favorite.saved)
                                }
                                if(article.forumAuthorId != null) ForumBlacklistAction(c,
                                    c.forumSession, c.forumApi.blacklist, article.forumAuthorId, article.user.username,
                                    authorBlocked) { authorBlocked = it }
                                if(ForumRules.canEditPost(article, profile)) {
                                    AppTextButton(onClick = { c.go("compose?article=$id") }) { Text("编辑") }
                                }
                                if(ForumRules.canDeletePost(article, profile, now)) {
                                    AppTextButton(onClick = { deletion = article }) { Text("删除") }
                                }
                            } }
                        }
                    } else ForumCommentsPanel(c, forumId, article.locked)
                }
            }
        }
    }
    deletion?.let { article -> ConfirmDialog("删除这篇文章？", "请确认已保存需要的内容。", { deletion = null }, confirmLabel = "删除文章") { c.action("文章已删除") {
        require(ForumRules.canDeletePost(article, c.forumSession.profile.value)) { "只有作者在发布后 20 分钟内或管理员可以删除帖子" }
        c.forumApi.deletePost(forumId)
        deletion = null; c.back()
    } } }
}
