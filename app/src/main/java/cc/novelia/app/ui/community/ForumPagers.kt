@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import cc.novelia.app.ui.components.base.AppPageTabs
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 分类用 slug 标识；点击与滑动共用选中回调，保持筛选重置规则一致。 */
@Composable internal fun ForumCategoryPager(
    categories: List<ForumCategory>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, controls: @Composable () -> Unit = {},
    content: @Composable (ForumCategory) -> Unit,
) {
    key(categories.map { it.slug }) {
        val pages = rememberSaveableStateHolder()
        val pager = rememberPagerState(initialPage = categories.indexOfFirst { it.slug == selected }.coerceAtLeast(0)) { categories.size }
        val scope = rememberCoroutineScope()
        val reducedMotion = appReducedMotion()
        val select by rememberUpdatedState(onSelect)
        val currentSelection by rememberUpdatedState(selected)
        LaunchedEffect(pager) {
            // 中途重建时以分页器恢复的位置为准，避免筛选分类与可见页面不一致。
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
                val slug = categories[it].slug
                if(slug != currentSelection) select(slug)
            }
        }
        Column(modifier.fillMaxSize()) {
            ForumCategoryTabs(categories, categories[pager.currentPage].slug) { slug ->
                // 其他分类在翻页落定后统一切换，动画期间保留原列表和筛选。
                if(slug == currentSelection) select(slug)
                scope.launch {
                    val index = categories.indexOfFirst { it.slug == slug }
                    if(reducedMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index)
                }
            }
            controls()
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("forum-category-pager"),
                key = { categories[it].slug }) { index ->
                pages.SaveableStateProvider(categories[index].slug) {
                    Box(Modifier.fillMaxSize()) { content(categories[index]) }
                }
            }
        }
    }
}

/** 分页承担横向过渡；正文位置与评论草稿使用各自稳定的保存键。 */
@Composable internal fun ArticleContentPager(
    selected: Int, onSelect: (Int) -> Unit, commentCount: Int, state: SaveableStateHolder,
    modifier: Modifier = Modifier, content: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = selected.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    val reducedMotion = appReducedMotion()
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { select(it) }
    }
    Column(modifier.fillMaxSize()) {
        AppPageTabs(pager.currentPage) {
            listOf("文章内容", "讨论 $commentCount").forEachIndexed { index, title ->
                Tab(pager.currentPage == index, onClick = {
                    select(index)
                    scope.launch { if(reducedMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index) }
                }, text = { Text(title) })
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("article-content-pager")) { index ->
            state.SaveableStateProvider(index) { Box(Modifier.fillMaxSize()) { content(index) } }
        }
    }
}
