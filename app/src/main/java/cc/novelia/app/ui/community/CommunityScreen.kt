@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import androidx.compose.animation.Crossfade
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
    var category by rememberSaveable { mutableStateOf("General") }; var page by rememberSaveable { mutableIntStateOf(0) }; var search by rememberSaveable { mutableStateOf("") }; var saved by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = appReducedMotion()
    val state by c.store.state.collectAsStateWithLifecycle()
    var draftBoxOpen by rememberSaveable { mutableStateOf(false) }
    Screen("社区", actions = {
        IconButton(onClick = { draftBoxOpen = true }) { Icon(Icons.Outlined.Drafts, "新帖草稿箱") }
        IconToggleButton(checked = saved, onCheckedChange = { saved = it }) {
            Crossfade(saved, animationSpec = tween(if(reducedMotion) 0 else AppMotion.Quick), label = "savedArticles") { showingSaved ->
                Icon(if(showingSaved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "已收藏的文章")
            }
        }
        IconButton(onClick = { c.go("compose") }) { Icon(Icons.Outlined.Edit, "新建帖子草稿") }
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
    if(draftBoxOpen) AppSheet(onDismissRequest = { draftBoxOpen = false }) { ArticleDraftBox(c) { draftBoxOpen = false } }
}
@Composable private fun ArticleList(c: AppController, result: Page<Article>, page: Int, changePage: (Int) -> Unit) {
    val reducedMotion = appReducedMotion()
    AppLazyColumn {
        if(result.items.isEmpty()) item { EmptyState("这里暂时没有文章", "试试其他分类，或调整搜索词。", Icons.Outlined.Forum) }
        items(result.items, key = { it.id }, contentType = { "article" }) { article ->
            Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
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
