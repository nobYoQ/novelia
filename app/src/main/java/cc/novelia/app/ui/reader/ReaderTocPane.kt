package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.SectionTitle
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list and filters belong to ReaderContent, so a pane/sheet change keeps their state. */
@Composable internal fun ReaderTocPane(
    c: AppController,
    ref: BookRef,
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
    AsyncContent(ref.key, load = {
        withContext(Dispatchers.IO) {
            if(ref.isLocal) c.store.documentIndex(ref.id).chapters.map { TocItem(it.title, it.title, it.id) }
            else c.detail<WebDetail>("novel/${ref.key}").toc
        }
    }, modifier = modifier.testTag("reader-toc-pane")) { toc, _ ->
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
            if(locateRequest > 0 && currentIndex >= 0) { locate(); onLocated() }
        }
        Column {
            Text("目录", Modifier.padding(start = 20.dp, top = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("reader-toc-query"),
                label = { Text("搜索章节") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onReversed(!reversed) }) { Text(if(reversed) "倒序" else "正序") }
                TextButton(onClick = { scope.launch(Dispatchers.Main.immediate) { locate() } }, enabled = currentIndex >= 0) { Text("定位当前") }
                if(query.isNotBlank()) TextButton(onClick = { onQuery("") }) { Text("清空") }
            }
            HorizontalDivider()
            AppLazyColumn(state = scroll, modifier = Modifier.weight(1f).testTag("reader-toc-list")) {
                items(entries, key = { it.value.chapterId?.let { id -> "chapter-$id" } ?: "section-${it.index}" }, contentType = { if(it.value.chapterId == null) "section" else "chapter" }) { entry ->
                    val item = entry.value
                    val id = item.chapterId
                    if(id == null) SectionTitle(item.title)
                    else ListItem(
                        headlineContent = { Text(item.title, color = if(id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingContent = { Icon(if(id == current) Icons.Outlined.Bookmark else Icons.Outlined.Article, null) },
                        supportingContent = if(id == current) ({ Text("正在阅读") }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("reader-toc-chapter-$id").semantics { selected = id == current }.motionClickable { onRead(id) }
                    )
                }
                if(entries.isEmpty()) item { EmptyState("没有匹配的章节", "修改搜索词或清空搜索后重试。") }
            }
        }
    }
}
