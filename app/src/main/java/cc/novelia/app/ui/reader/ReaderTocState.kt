package cc.novelia.app.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.novelia.app.data.model.TocItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一本书的阅读会话共用目录；切章、重开面板和布局变化不重新加载已有目录。 */
internal class ReaderTocState(private val load: suspend (forceNetwork: Boolean) -> List<TocItem>) {
    var toc by mutableStateOf<List<TocItem>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Exception?>(null)
        private set
    private val mutex = Mutex()
    private data class Missing(val chapter: String?, val count: Int)
    private var lastAttempt: Missing? = null

    private fun missing(chapterId: String, knownCount: Int): Missing {
        val ids = toc?.mapNotNull { it.chapterId }.orEmpty()
        return Missing(chapterId.takeUnless { it in ids }, knownCount.takeIf { it > ids.size } ?: 0)
    }

    suspend fun ensureLoaded(chapterId: String, knownCount: Int, refresh: Boolean = false) = mutex.withLock {
        val required = missing(chapterId, knownCount)
        if(!refresh && ((toc != null && required == Missing(null, 0)) || required == lastAttempt)) return@withLock
        loading = true
        error = null
        try {
            val forceNetwork = refresh || toc != null
            val loaded = load(forceNetwork)
            currentCoroutineContext().ensureActive()
            toc = loaded
            // 初次读取可能命中旧磁盘快照；只有缺章或落后于已知总数时才补查一次。
            if(!forceNetwork && missing(chapterId, knownCount) != Missing(null, 0)) {
                val updated = load(true)
                currentCoroutineContext().ensureActive()
                toc = updated
            }
            lastAttempt = missing(chapterId, knownCount)
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            currentCoroutineContext().ensureActive()
            error = e
            // 服务端仍缺章或暂时失败时，同一缺口不随重组/切章反复请求；允许显式重试。
            lastAttempt = missing(chapterId, knownCount)
        } finally {
            loading = false
        }
    }
}
