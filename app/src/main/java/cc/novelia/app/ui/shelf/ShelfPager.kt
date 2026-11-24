@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import cc.novelia.app.ui.components.base.AppHorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import cc.novelia.app.ui.components.base.AppPageTabs
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 点击和滑动共用分页状态；分区落定后才退出批量整理，各页单独保存滚动和筛选控件。 */
@Composable internal fun ShelfPager(
    selected: Int, onSelect: (Int) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, controls: @Composable () -> Unit = {},
    content: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = selected.coerceIn(0, 2)) { 3 }
    val pages = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val reducedMotion = appReducedMotion()
    val focus = LocalFocusManager.current
    val select by rememberUpdatedState(onSelect)
    val currentSelection by rememberUpdatedState(selected)
    LaunchedEffect(pager) {
        // 包含首次发射，让中途重建后恢复的分页位置重新同步到外层。
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            if(page != currentSelection) {
                focus.clearFocus()
                select(page)
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        val titles = listOf("我的书架", "本地文件", "云端收藏")
        AppPageTabs(pager.currentPage, titles.size) { index ->
            Tab(pager.currentPage == index, onClick = {
                scope.launch {
                    if(reducedMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index)
                }
            }, enabled = enabled, text = { Text(titles[index]) }, modifier = Modifier.testTag("shelf-tab-$index"))
        }
        controls()
        AppHorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("shelf-pager"), userScrollEnabled = enabled,
            key = { it }) { page ->
            pages.SaveableStateProvider(page) {
                Box(Modifier.fillMaxSize().testTag("shelf-page-$page")) { content(page) }
            }
        }
    }
}
