package cc.novelia.app.data.chapters

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal const val MAX_CONCURRENT_CHAPTERS = 3

/** 使用固定数量的工作协程，不为每章单独创建；失败或取消停止整批，进度回调串行递增。 */
internal suspend fun cacheChapterBatch(
    ids: List<String>,
    slots: Semaphore = Semaphore(MAX_CONCURRENT_CHAPTERS),
    load: suspend (String) -> Unit,
    progress: suspend (Int, Int) -> Unit,
) = coroutineScope {
    val chapters = ids.distinct()
    val lock = Mutex()
    var next = 0
    var completed = 0
    List(minOf(MAX_CONCURRENT_CHAPTERS, chapters.size)) {
        async {
            while (true) {
                ensureActive()
                val id = lock.withLock { chapters.getOrNull(next)?.also { next++ } } ?: break
                slots.withPermit { load(id) }
                ensureActive()
                lock.withLock { progress(++completed, chapters.size) }
            }
        }
    }.awaitAll()
    Unit
}
