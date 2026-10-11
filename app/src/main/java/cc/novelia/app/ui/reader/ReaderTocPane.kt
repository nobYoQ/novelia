package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.library.offlineRangeLabel
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.components.base.SectionTitle
import cc.novelia.app.ui.components.base.rememberDebouncedQuery
import cc.novelia.app.ui.components.book.rememberCachedChapterIds
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 目录列表和筛选由 ReaderContent 持有，侧栏与弹层切换时保留状态。 */
@Composable internal fun ReaderTocPane(
    c: AppController,
    ref: BookRef,
    state: ReaderTocState,
    onRetry: () -> Unit,
    current: String,
    scroll: LazyListState,
    query: String,
    onQuery: (String) -> Unit,
    reversed: Boolean,
    onReversed: (Boolean) -> Unit,
    locateRequest: Int,
    onLocated: () -> Unit,
    onRead: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = appReducedMotion()
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize().testTag("reader-toc-pane")) {
        val toc = state.toc
        if(toc == null) {
            if(state.error != null && !state.loading) EmptyState("暂时无法加载目录", state.error.friendlyMessage(), action = "重试", onAction = onRetry)
            else Column(Modifier.align(Alignment.Center).testTag("reader-toc-loading"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if(!reducedMotion) CircularProgressIndicator()
                Text("正在加载目录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Box
        }
        val cachedIds = rememberCachedChapterIds(c.store, ref, toc)
        val settledQuery = rememberDebouncedQuery(query)
        val indexed = remember(toc) { toc.withIndex().toList() }
        val entries = remember(indexed, settledQuery, reversed) {
            indexed.filter { it.value.title.contains(settledQuery, true) || it.value.titleJp.contains(settledQuery, true) }
                .let { if(reversed) it.reversed() else it }
        }
        val currentIndex = remember(entries, current) { entries.indexOfFirst { it.value.chapterId == current } }
        suspend fun locate() {
            if(currentIndex >= 0) {
                if(reducedMotion || abs(currentIndex - scroll.firstVisibleItemIndex) > 100) scroll.scrollToItem(currentIndex)
                else scroll.animateScrollToItem(currentIndex)
            }
        }
        LaunchedEffect(locateRequest, currentIndex) {
            // 初次打开立即定位到当前章，长书中较靠后的章节也无需长距离动画。
            if(locateRequest > 0) { scroll.scrollToItem(currentIndex.coerceAtLeast(0)); onLocated() }
        }
        Column {
            Text("目录", Modifier.padding(start = 20.dp, top = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("reader-toc-query"),
                label = { Text("搜索章节") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            Row(Modifier.padding(horizontal = 8.dp)) {
                AppTextButton(onClick = { onReversed(!reversed) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if(reversed) "倒序" else "正序") }
                AppTextButton(onClick = { scope.launch(Dispatchers.Main.immediate) { locate() } }, enabled = currentIndex >= 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("定位当前") }
                if(query.isNotBlank()) AppTextButton(onClick = { onQuery("") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空") }
            }
            state.error?.let {
                AppTextButton(onClick = onRetry, enabled = !state.loading, modifier = Modifier.padding(horizontal = 8.dp)) { Text("目录更新失败，点击重试") }
            }
            if(!ref.isLocal) Text(remember(toc, cachedIds) { offlineRangeLabel(toc, cachedIds) },
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("reader-toc-cache-summary"),
                style = MaterialTheme.typography.labelMedium)
            HorizontalDivider()
            AppLazyColumn(state = scroll, modifier = Modifier.weight(1f).testTag("reader-toc-list")) {
                items(entries, key = { it.value.chapterId?.let { id -> "chapter-$id" } ?: "section-${it.index}" }, contentType = { if(it.value.chapterId == null) "section" else "chapter" }) { entry ->
                    val item = entry.value
                    val id = item.chapterId
                    if(id == null) SectionTitle(item.title)
                    else ListItem(
                        headlineContent = { Text(item.title, color = if(id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingContent = { Icon(if(id == current) Icons.Outlined.Bookmark else Icons.Outlined.Article, null) },
                        supportingContent = if(id == current || (!ref.isLocal && id in cachedIds)) ({
                            Text(listOfNotNull("正在阅读".takeIf { id == current },
                                "可离线阅读".takeIf { !ref.isLocal && id in cachedIds }).joinToString(" · "))
                        }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("reader-toc-chapter-$id").semantics { selected = id == current }.motionClickable { onRead(id) }
                    )
                }
                if(entries.isEmpty()) item { EmptyState("没有匹配的章节", "修改搜索词或清空搜索后重试。") }
            }
        }
    }
}
