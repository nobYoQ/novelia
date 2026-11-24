@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import cc.novelia.app.ui.components.base.AppHorizontalPager
import androidx.compose.foundation.pager.PagerState
import cc.novelia.app.ui.components.base.AppPageTabs
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable internal fun rememberDiscoverNavigation(pager: PagerState, onNavigate: () -> Unit): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    val reducedMotion = appReducedMotion()
    val navigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(pager) {
        // 等手势落定再结束编辑；拖出邻页又撤回时保留正在输入的草稿。
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { navigate() }
    }
    return remember(pager, scope, reducedMotion) {
        { section: Int ->
            navigate()
            scope.launch { if(reducedMotion) pager.scrollToPage(section) else pager.animateScrollToPage(section) }
            Unit
        }
    }
}

/** 分页位置是唯一的分区选择状态，搜索跳转、标签点击和滑动共用它。 */
@Composable internal fun DiscoverPager(
    pager: PagerState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val pages = rememberSaveableStateHolder()
    Column(modifier.fillMaxSize()) {
        val titles = listOf("为你发现", "网络小说", "文库小说")
        AppPageTabs(pager.currentPage, titles.size) { index ->
            Tab(pager.currentPage == index, onClick = { onSelect(index) },
                text = { Text(titles[index], maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("discover-tab-$index"))
        }
        AppHorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("discover-pager"), key = { it }) { section ->
            pages.SaveableStateProvider(section) {
                Box(Modifier.fillMaxSize().testTag("discover-section-$section")) { content(section) }
            }
        }
    }
}
