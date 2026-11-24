package cc.novelia.app.data.chapters

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.cache.ChapterCacheGeneration
import cc.novelia.app.data.model.BookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ChapterCacheKey(val book: BookRef, val binding: SessionBinding)
internal data class ChapterCacheRequest(val key: ChapterCacheKey, val generation: ChapterCacheGeneration,
    val first: Int, val ids: List<String>, val wifiOnly: Boolean)
internal data class ChapterCacheProgress(val first: Int, val total: Int, val wifiOnly: Boolean,
    val completed: Int = 0, val result: String? = null)

/** 同一本书同一会话只运行一批。弹窗订阅进度，只有显式取消才会停止应用级任务。 */
internal class ChapterCacheQueue(private val scope: CoroutineScope,
    private val cache: suspend (ChapterCacheRequest, suspend (Int, Int) -> Unit) -> Unit,
) {
    private val jobs = mutableMapOf<ChapterCacheKey, Job>()
    private val mutable = MutableStateFlow<Map<ChapterCacheKey, ChapterCacheProgress>>(emptyMap())
    val progress = mutable.asStateFlow()

    @Synchronized fun start(request: ChapterCacheRequest) {
        val key = request.key
        if (key in jobs) return
        mutable.update { states ->
            val recent = states.entries.filter { it.value.result != null }.takeLast(24)
            states.filterValues { it.result == null } + recent.associate { it.toPair() } +
                (key to ChapterCacheProgress(request.first, request.ids.size, request.wifiOnly))
        }
        val job = scope.async(start = CoroutineStart.LAZY) {
            cache(request) { done, _ ->
                mutable.update { it + (key to it.getValue(key).copy(completed = done)) }
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { cause -> synchronized(this) {
            jobs.remove(key)
            val result = when (cause) {
                null -> "缓存完成，可在目录查看当前离线范围。"
                is CancellationException -> "已取消，已完成的缓存会保留。"
                else -> cause.message?.take(160) ?: "缓存中断，请稍后重试。"
            }
            mutable.update { it + (key to it.getValue(key).copy(result = result)) }
        } }
        job.start()
    }

    @Synchronized fun cancel(key: ChapterCacheKey) { jobs[key]?.cancel() }
}
