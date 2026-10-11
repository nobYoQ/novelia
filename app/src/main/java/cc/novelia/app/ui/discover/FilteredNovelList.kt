package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.FilteredNovelBatch
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.catalog.loadFilteredNovels
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Page
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.friendlyMessage

/** 本地筛选使用继续加载游标，不把原站页数当成筛选后的总页数。 */
@Composable internal fun FilteredNovelList(c: AppController, requestKey: Any, filter: NovelLocalFilter, library: LibraryState,
    loadPage: suspend (Int, Boolean) -> Page<BookCard>, onReset: () -> Unit, keywordLabels: Map<String, String> = emptyMap(),
    onPageTurn: (Int) -> Unit = {}) {
    var result by remember(requestKey) { mutableStateOf(c.filteredDiscoverPage?.takeIf { it.first == requestKey }?.second) }
    var request by remember(requestKey) { mutableIntStateOf(0) }
    var loading by remember(requestKey) { mutableStateOf(result == null) }
    var error by remember(requestKey) { mutableStateOf<String?>(null) }
    var forceNetwork by remember(requestKey) { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(loadPage)
    LaunchedEffect(requestKey, request) {
        if(request == 0 && result != null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val before = result ?: FilteredNovelBatch()
            fun publish(batch: FilteredNovelBatch) {
                result = batch.copy(books = (before.books + batch.books).distinctBy { it.ref.key },
                    scanned = before.scanned + batch.scanned, unknown = before.unknown + batch.unknown)
                c.filteredDiscoverPage = requestKey to result!!
            }
            val batch = loadFilteredNovels(before.nextPage, filter, loadPage = { currentLoad(it, forceNetwork) },
                enrich = { enrichAuthors(it, c, library.blockedAuthors, filter.characters.active, forceNetwork) },
                visible = { visibleBook(it, library) }, onProgress = { publish(it) })
            currentCoroutineContext().ensureActive()
            publish(batch)
        } catch(e: CancellationException) { throw e }
          catch(e: Exception) { error = e.friendlyMessage() }
        finally { loading = false; forceNetwork = false }
    }
    val scroll = key(requestKey) { rememberLazyListState() }
    val reducedMotion = appReducedMotion()
    MotionContent(listOf(requestKey, result != null), Modifier.fillMaxSize(), animateInitial = result != null) {
    AppLazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), onPageTurn = onPageTurn) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("已检查 ${result?.scanned ?: 0} 本，找到 ${result?.books?.size ?: 0} 本", style = MaterialTheme.typography.bodySmall)
                if((result?.unknown ?: 0) > 0) Text("其中 ${result!!.unknown} 本字数未知${if(filter.characters.includeUnknown) "，已按当前条件保留" else "，未计入结果"}", style = MaterialTheme.typography.bodySmall)
                if(loading) Text("正在查找…", Modifier.testTag("local-filter-loading"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(result?.books.orEmpty(), key = { it.ref.key }) { book ->
            DiscoverBookRow(book, { c.book(book.ref) },
                modifier = if(reducedMotion) Modifier else Modifier.animateItem(
                    fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)),
                keywordLabels = keywordLabels, showCharacterCount = filter.characters.active)
        }
        item {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(!loading && result?.books.isNullOrEmpty() && error == null) Text(if(result?.endReached == true) "已检查完当前搜索结果，没有符合条件的作品。" else "暂未找到符合条件的作品，可以继续查找。")
                if(result?.endReached != true || error != null) AppButton(onClick = { request++ }, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("continue-filtered-search")) { Text(if(error == null) "继续查找" else "重试") }
                if(result?.endReached == true) Text("已检查完当前搜索结果", style = MaterialTheme.typography.bodySmall)
                AppTextButton(onClick = { result = null; c.filteredDiscoverPage = null; forceNetwork = true; request++ }, enabled = !loading) { Text("重新查找") }
                AppTextButton(onClick = onReset) { Text("清空筛选") }
            }
        }
    }
    }
}
