@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.data.catalog.ForumLinks
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.ForumRules
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion

@Composable fun ArticleScreen(c: AppController, id: String) {
    val forumId = ForumLinks.postId(id)
    val profile by (if(forumId != null) c.forumSession else c.session).profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }; var deletion by remember(id) { mutableStateOf<Article?>(null) }
    val tabState = rememberSaveableStateHolder()
    val reducedMotion = appReducedMotion()
    Screen("文章", c::back, actions = { IconButton(onClick = { c.share(ForumLinks.articleUrl(id)) }) { Icon(Icons.Outlined.Share, "分享文章") } }) { padding ->
        AsyncContent(listOf(id, profile?.username, profile?.userId, profile?.role), load = { c.article(id) }, modifier = Modifier.padding(padding)) { article, _ ->
            val now = rememberForumModificationTime(article.createAt)
            var cloudSaved by remember(article) { mutableStateOf(article.forumFavorited) }
            var saving by remember { mutableStateOf(false) }
            Column {
                if(forumId != null) ForumRulesReminder(c)
                PrimaryTabRow(tab) { listOf("文章内容", "讨论 ${article.numComments}").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
                MotionContent(tab, Modifier.weight(1f), animateInitial = false) {
                    tabState.SaveableStateProvider(tab) {
                        if(tab == 0) {
                        val articleScroll = rememberLazyListState()
                        AppLazyColumn(state = articleScroll, contentPadding = PaddingValues(20.dp)) {
                            item { Text(categories[article.category] ?: article.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(12.dp)); Text(article.title, style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("${article.user.username} · ${displayDate(article.createAt)} · ${article.numViews} 次浏览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if(article.forumTags.isNotEmpty()) Text(article.forumTags.joinToString(" · ") { it.name }); Spacer(Modifier.height(24.dp)) }
                            item { MarkdownText(c, article.content, documentUrl = ForumLinks.articleUrl(id),
                                onAnchorScroll = { top -> articleScroll.scrollToItem(1, top) }) }
                            item { Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val saved = state.savedArticles.any { it.id == id }
                                OutlinedButton(onClick = { c.store.update { it.copy(savedArticles = if(saved) it.savedArticles.filterNot { a -> a.id == id } else it.savedArticles + article.copy(id = id)) } }) {
                                    Crossfade(saved, animationSpec = tween(if(reducedMotion) 0 else AppMotion.Quick), label = "articleBookmark") { isSaved ->
                                        Icon(if(isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(if(saved) "取消收藏" else "收藏文章")
                                }
                                if(if(forumId != null) ForumRules.canEditPost(article, profile) else profile?.username == article.user.username) {
                                    TextButton(onClick = { c.go("compose?article=$id") }) { Text("编辑") }
                                }
                                if(if(forumId != null) ForumRules.canDeletePost(article, profile, now) else profile?.username == article.user.username) {
                                    TextButton(onClick = { deletion = article }) { Text("删除") }
                                }
                            } }
                            if(forumId != null) item { TextButton(enabled = !saving, onClick = { c.requireForumLogin { c.action { saving = true; try { c.forumApi.favorite(forumId, !cloudSaved); cloudSaved = !cloudSaved } finally { saving = false } } } }) { Text(if(cloudSaved) "取消云端收藏" else "收藏到论坛账号") } }
                        }
                        } else if(forumId != null) ForumCommentsPanel(c, forumId, article.locked) else CommentsPanel(c, "article-$id", article.locked)
                    }
                }
            }
        }
    }
    deletion?.let { article -> ConfirmDialog("删除这篇文章？", "请确认已保存需要的内容。", { deletion = null }, confirmLabel = "删除文章") { c.action("文章已删除") {
        if(forumId != null) {
            require(ForumRules.canDeletePost(article, c.forumSession.profile.value)) { "只有作者在发布后 20 分钟内或管理员可以删除帖子" }
            c.forumApi.deletePost(forumId)
        } else c.api.request("DELETE", "article/$id")
        deletion = null; c.back()
    } } }
}
