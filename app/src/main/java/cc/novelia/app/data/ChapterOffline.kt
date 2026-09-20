package cc.novelia.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

/** Owns a session/cache generation for a bounded, cancellable sequential batch. */
class ChapterOffline(private val store: LocalStore, private val api: NoveliaApi, private val session: AuthenticationSession) {
    suspend fun cache(ref: BookRef, ids: List<String>, wifiOnly: Boolean, progress: suspend (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        require(!ref.isLocal && !ref.isWenku)
        require(ids.size <= 200)
        val binding = session.capture()
        val generation = store.cacheGeneration.value
        val distinctIds = ids.distinct()
        for((index, id) in distinctIds.withIndex()) {
            currentCoroutineContext().ensureActive()
            session.ensureCurrent(binding)
            if(store.cacheGeneration.value != generation) throw CancellationException("缓存已清理")
            if(store.cachedChapter(ref, id) == null) {
                check(chapterNetworkAllowed(store.context, wifiOnly)) { if(wifiOnly) "已暂停：当前不是 Wi-Fi 网络" else "已暂停：网络不可用" }
                store.chapterRequests.load(api, session, binding, generation, ref, id)
                currentCoroutineContext().ensureActive()
                session.ensureCurrent(binding)
                // A network policy change must not allow the next request in this batch.
                check(store.cachedChapter(ref, id) != null) { "章节缓存未保存，请检查可用存储空间" }
            }
            progress(index + 1, distinctIds.size)
        }
    }

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
