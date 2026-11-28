package cc.novelia.app.ui.components.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable internal fun CachedChapterStatus(store: LocalStore, ref: BookRef, id: String, reading: Boolean = false) {
    val revision by store.chapterCacheRevision.collectAsStateWithLifecycle()
    var illustrated by remember(store, ref, id) { mutableStateOf(false) }
    LaunchedEffect(store, ref, id, revision) {
        illustrated = withContext(Dispatchers.IO) { store.cachedChapterHasIllustration(ref, id) }
    }
    ChapterStatus(if (reading) "正在阅读 · 可离线阅读" else "可离线阅读", illustrated)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChapterStatus(label: String? = null, illustrated: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label)
        if (illustrated) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("含插图")
        }
    }
}
