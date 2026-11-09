@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 点击和滑动共用分页状态；分区落定后才退出批量整理，各页单独保存滚动和筛选控件。 */
@Composable internal fun ShelfPager(
    selected: Int, onSelect: (Int) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, controls: @Composable () -> Unit = {}, singlePage: Boolean = false,
    classifyingBooks: Boolean = false,
    content: @Composable (Int) -> Unit,
) {
    val pages = rememberSaveableStateHolder()
    val pageContent: @Composable (Int) -> Unit = { page ->
        Box(Modifier.fillMaxSize().testTag("shelf-page-$page")) {
            // 等书目归类后再挂载本地列表，避免临时空列表在测量时覆盖已保存的滚动位置。
            if (classifyingBooks && page != 2) Text("正在整理已有书籍…", Modifier.align(Alignment.Center))
            else pages.SaveableStateProvider(page) { content(page) }
        }
    }
    if (singlePage) {
        Column(modifier.fillMaxSize()) {
            controls()
            Box(Modifier.weight(1f).fillMaxWidth()) { pageContent(0) }
        }
        return
    }
    val pager = rememberPagerState(initialPage = selected.coerceIn(0, 2)) { 3 }
    val scope = rememberCoroutineScope()
    val reducedMotion = appReducedMotion()
    val focus = LocalFocusManager.current
    val select by rememberUpdatedState(onSelect)
    val currentSelection by rememberUpdatedState(selected)
    var previousRequest by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != previousRequest && pager.settledPage != selected) pager.scrollToPage(selected)
        previousRequest = selected
    }
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
        PrimaryTabRow(pager.currentPage) {
            listOf("我的书架", "本地文件", "云端收藏").forEachIndexed { index, title ->
                Tab(pager.currentPage == index, onClick = {
                    scope.launch {
                        if(reducedMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index)
                    }
                }, enabled = enabled, text = { Text(title) }, modifier = Modifier.testTag("shelf-tab-$index"))
            }
        }
        controls()
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("shelf-pager"), userScrollEnabled = enabled,
            key = { it }) { page -> pageContent(page) }
    }
}
