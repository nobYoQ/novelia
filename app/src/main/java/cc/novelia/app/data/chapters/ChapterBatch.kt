package cc.novelia.app.data.chapters

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 固定数量 worker，不为每个章节创建协程；失败/取消停止整批，进度回调串行递增。 */
internal suspend fun cacheChapterBatch(
    ids: List<String>,
    load: suspend (String) -> Unit,
    progress: suspend (Int, Int) -> Unit,
) = coroutineScope {
    val chapters = ids.distinct()
    val lock = Mutex()
    var next = 0
    var completed = 0
    repeat(minOf(3, chapters.size)) {
        launch {
            while (true) {
                ensureActive()
                val id = lock.withLock { chapters.getOrNull(next)?.also { next++ } } ?: break
                load(id)
                ensureActive()
                lock.withLock { progress(++completed, chapters.size) }
            }
        }
    }
}
