@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

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
    val profile by c.session.profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }; var deletion by remember { mutableStateOf(false) }
    val tabState = rememberSaveableStateHolder()
    val reducedMotion = appReducedMotion()
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
                                    Crossfade(saved, animationSpec = tween(if(reducedMotion) 0 else AppMotion.Quick), label = "articleBookmark") { isSaved ->
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
    if(deletion) ConfirmDialog("删除这篇文章？", "删除后将从原站移除，请确认已保存需要的内容。", { deletion = false }, confirmLabel = "删除文章") { c.action("文章已删除") { c.api.request("DELETE", "article/$id"); c.back() } }
}
