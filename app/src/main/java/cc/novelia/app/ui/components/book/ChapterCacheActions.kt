package cc.novelia.app.ui.components.book

import androidx.compose.runtime.*
import cc.novelia.app.data.cache.ChapterCacheSummary
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun chapterCacheSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Stable internal class ChapterCacheActions(private val c: AppController) {
    var selected by mutableStateOf<ChapterCacheSummary?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun dismiss() { selected = null }
    fun request(summary: ChapterCacheSummary) { if(!busy) selected = summary }
    fun request(ref: BookRef) {
        if(busy) return
        busy = true
        c.action {
            try {
                val summary = withContext(Dispatchers.IO) { c.store.chapterCacheSummary(ref) }
                if(summary.chapters == 0) c.message("本书暂无章节缓存") else selected = summary
            } finally { busy = false }
        }
    }

    fun clear() {
        val summary = selected ?: return
        busy = true
        c.action("章节缓存已清理") {
            try { withContext(Dispatchers.IO) { c.store.clearChapterCache(summary.ref) } }
            finally { busy = false }
        }
    }
}

@Composable internal fun rememberChapterCacheActions(c: AppController): ChapterCacheActions {
    val actions = remember(c) { ChapterCacheActions(c) }
    actions.selected?.let { summary ->
        ConfirmDialog("清理${if(summary.ref == null) "旧版" else "本书"}章节缓存？",
            "${summary.title}\n已缓存 ${summary.chapters} 章 · ${chapterCacheSize(summary.bytes)}\n\n清理后需联网重新加载正文。阅读进度、书签、本地小说和下载文件会保留。",
            actions::dismiss, confirmLabel = "清理章节缓存", onConfirm = actions::clear)
    }
    return actions
}
