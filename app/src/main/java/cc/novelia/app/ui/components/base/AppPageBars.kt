@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components.base

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import cc.novelia.app.ui.theme.LocalEInkMode
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.ThemeBar
import cc.novelia.app.ui.theme.monochromeDivider
import cc.novelia.app.ui.theme.themeBarBackground

@Composable internal fun AppTopBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}) {
    val bar = themeBarBackground(ThemeBar.Top, TopAppBarDefaults.topAppBarColors().containerColor)
    TopAppBar(title = title, modifier = bar.modifier.monochromeDivider().testTag("page-top-bar"), navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = bar.color, scrolledContainerColor = bar.color))
}

@Composable internal fun AppPageTabs(index: Int, count: Int, scrollable: Boolean = false, tab: @Composable (Int) -> Unit) {
    val bar = themeBarBackground(ThemeBar.Top, TabRowDefaults.primaryContainerColor)
    val modifier = bar.modifier.testTag("page-tabs")
    if(LocalEInkMode.current) Surface(modifier, color = bar.color, contentColor = TabRowDefaults.primaryContentColor) {
        Column {
            if(scrollable) {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val direction = if(LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
                LaunchedEffect(index) {
                    val layout = list.layoutInfo
                    val selected = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    when {
                        selected == null -> list.scrollToItem(index)
                        selected.offset < layout.viewportStartOffset -> list.scrollBy((selected.offset - layout.viewportStartOffset).toFloat())
                        selected.offset + selected.size > layout.viewportEndOffset ->
                            list.scrollBy((selected.offset + selected.size - layout.viewportEndOffset).toFloat())
                        else -> Unit
                    }
                }
                LazyRow(state = list, userScrollEnabled = false, contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth().screenPageSwipe(horizontal = true) { delta ->
                        scope.launch { list.scrollBy((delta * direction * list.layoutInfo.viewportSize.width).toFloat()) }
                    }) {
                    items(count) { i -> StaticPageTab(i == index, Modifier.widthIn(min = 90.dp)) { tab(i) } }
                }
            } else Row(Modifier.fillMaxWidth()) {
                repeat(count) { i -> StaticPageTab(i == index, Modifier.weight(1f)) { tab(i) } }
            }
            HorizontalDivider()
        }
    } else if(scrollable) PrimaryScrollableTabRow(index, modifier = modifier, containerColor = bar.color,
        contentColor = TabRowDefaults.primaryContentColor, edgePadding = 12.dp) { repeat(count) { tab(it) } }
    else PrimaryTabRow(index, modifier = modifier, containerColor = bar.color,
        contentColor = TabRowDefaults.primaryContentColor) { repeat(count) { tab(it) } }
}

@Composable private fun StaticPageTab(selected: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.BottomCenter, propagateMinConstraints = true) {
        key(selected) { content() }
        if(selected) Box(Modifier.wrapContentSize(Alignment.BottomCenter).size(28.dp, 3.dp)
            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
    }
}
