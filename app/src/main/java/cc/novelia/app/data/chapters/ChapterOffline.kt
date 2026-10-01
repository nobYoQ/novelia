package cc.novelia.app.data.chapters

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.storage.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

fun chapterNetworkAllowed(context: Context, wifiOnly: Boolean): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
}

/**
 * 手动离线缓存最多三章并行，自动预读沿 nextId 顺序执行；整批捕获账号绑定和缓存代次。
 * 每章检查取消与账号状态，新增网络读取前检查网络策略；清理缓存后不能继续用旧代次写入。
 * 手动缓存检查实际落盘结果，自动预读则尽力改善体验，失败由阅读页面处理而不替换当前正文。
 */
class ChapterOffline(private val store: LocalStore, private val api: NoveliaApi, private val session: AuthenticationSession) {
    suspend fun cache(ref: BookRef, ids: List<String>, wifiOnly: Boolean, progress: suspend (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        require(!ref.isLocal && !ref.isWenku)
        require(ids.size <= 200)
        val binding = session.capture()
        val generation = store.cacheGeneration.value
        cacheChapterBatch(ids, load = { id ->
            currentCoroutineContext().ensureActive()
            session.ensureCurrent(binding)
            if(store.cacheGeneration.value != generation) throw CancellationException("缓存已清理")
            if(store.cachedChapter(ref, id) == null) {
                check(chapterNetworkAllowed(store.context, wifiOnly)) { if(wifiOnly) "已暂停：当前不是 Wi-Fi 网络" else "已暂停：网络不可用" }
                store.chapterRequests.load(api, session, binding, generation, ref, id)
                currentCoroutineContext().ensureActive()
                session.ensureCurrent(binding)
                // 网络策略改变后，本批次不得继续发出下一项请求。
                check(store.cachedChapter(ref, id) != null) { "章节缓存未保存，请检查可用存储空间" }
            }
        }, progress = progress)
    }

    /** 沿 nextId 最多预读五章，使用 visited 防止服务端章节链接形成环；已有缓存可直接复用。 */
    suspend fun prefetch(ref: BookRef, first: String?, count: Int, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        if(first == null || count <= 0 || ref.isLocal || ref.isWenku) return@withContext
        val binding = session.capture()
        val generation = store.cacheGeneration.value
        val visited = mutableSetOf<String>()
        var next: String? = first
        repeat(count.coerceIn(0, 5)) {
            currentCoroutineContext().ensureActive()
            val id = next ?: return@withContext
            if(!visited.add(id)) return@withContext
            session.ensureCurrent(binding)
            if(generation != store.cacheGeneration.value || !chapterNetworkAllowed(store.context, wifiOnly)) return@withContext
            val cached = store.cachedChapter(ref, id)
            val chapter = cached ?: store.chapterRequests.load(api, session, binding, generation, ref, id)
            currentCoroutineContext().ensureActive()
            session.ensureCurrent(binding)
            next = chapter.nextId
        }
    }
}
