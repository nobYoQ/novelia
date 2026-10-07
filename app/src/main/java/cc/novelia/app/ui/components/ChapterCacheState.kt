package cc.novelia.app.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.storage.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 两处目录共用同一缓存判断，读取、预取、批量缓存及清理后同步更新。 */
@Composable internal fun rememberCachedChapterIds(store: LocalStore, ref: BookRef, toc: List<TocItem>): Set<String> {
    val revision by store.chapterCacheRevision.collectAsStateWithLifecycle()
    var cached by remember(store, ref, toc) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(store, ref, toc, revision) {
        cached = withContext(Dispatchers.IO) {
            buildSet {
                toc.forEach { item ->
                    currentCoroutineContext().ensureActive()
                    item.chapterId?.let { id -> if(ref.isLocal || store.chapterFile(ref, id).isFile) add(id) }
                }
            }
        }
    }
    return cached
}
