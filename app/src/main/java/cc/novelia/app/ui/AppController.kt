package cc.novelia.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavHostController
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.decodeFromString
import java.io.File

class AppController(val app: NoveliaApplication, val nav: NavHostController, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    val store get() = app.store
    val api get() = app.api
    val session get() = app.session
    val metadataCache get() = app.metadataCache
    var afterLogin: (() -> Unit)? = null
    private var celebration: Job? = null
    fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
    fun back() { nav.popBackStack() }
    fun book(ref: BookRef) {
        if (ref.isLocal) action {
            val chapter = store.state.value.positions[ref.key]?.chapterId ?: withContext(Dispatchers.IO) {
                store.document(ref.id).chapters.firstOrNull()?.id ?: error("这本小说没有可阅读的章节")
            }
            read(ref, chapter)
        } else go("book/${ref.provider}/${ref.id}")
    }
    fun read(ref: BookRef, chapter: String) = go("reader/${ref.provider}/${ref.id}/${Uri.encode(chapter)}")
    fun openLink(text: String) {
        when(val link = BookLinks.parse(text)) {
            is SiteLink.Book -> if(link.chapterId != null) read(link.ref, link.chapterId) else book(link.ref)
            is SiteLink.Post -> go("article/${link.id}")
            null -> go("discover?query=${Uri.encode(text)}")
        }
    }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun celebrate(text: String, sticker: MidoriSticker) {
        celebration?.cancel()
        celebration = scope.launch { snackbar.showSnackbar(StickerSnackbarVisuals(text, sticker)) }
    }
    fun action(success: String? = null, sticker: MidoriSticker? = null, block: suspend () -> Unit) { scope.launch(Dispatchers.Main.immediate) {
        try { block(); success?.let { if(sticker != null) celebrate(it, sticker) else snackbar.showSnackbar(it) } }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { snackbar.showSnackbar(e.friendlyMessage()) }
    } }
    fun requireLogin(action: () -> Unit) { if(session.profile.value == null) { afterLogin = action; go("login") } else action() }
    fun external(url: String) {
        val uri = Uri.parse(url)
        if(uri.scheme !in listOf("https", "http")) { message("不支持此链接类型"); return }
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { message("设备没有可用的浏览器") }
    }
    fun share(text: String) { app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    suspend inline fun <reified T> detail(path: String, forceNetwork: Boolean = false): T = withContext(Dispatchers.IO) {
        val account = session.profile.value?.username ?: "guest"
        val key = hashName("$account:$path")
        val mutation = api.lastMutationAt
        val generation = store.cacheGeneration.value
        if (!forceNetwork) {
            metadataCache.read(key, maxAgeMillis = 5 * 60_000L, newerThan = mutation)?.let { raw ->
                runCatching { appJson.decodeFromString<T>(raw) }.getOrNull()?.let { return@withContext it }
            }
        }
        try {
            val fetchedAt = System.currentTimeMillis()
            val raw = api.request("GET", path)
            val parsed = appJson.decodeFromString<T>(raw)
            if (mutation == api.lastMutationAt && generation == store.cacheGeneration.value && account == (session.profile.value?.username ?: "guest")) {
                runCatching { store.withCacheGeneration(generation) { metadataCache.write(key, raw, fetchedAt) } }
            }
            parsed
        } catch (e: IOException) {
            if (e is ApiException) throw e
            val cached = metadataCache.read(key) ?: throw e
            runCatching { appJson.decodeFromString<T>(cached) }.getOrElse { throw e }
        }
    }
    suspend fun chapter(ref: BookRef, id: String, forceNetwork: Boolean = false): Pair<Chapter, Boolean> = withContext(Dispatchers.IO) {
        if(ref.isLocal) {
            val doc = store.document(ref.id); val index = doc.chapters.indexOfFirst { it.id == id }.coerceAtLeast(0); val c = doc.chapters[index]
            Chapter(c.title, c.title, doc.name, doc.name, doc.chapters.getOrNull(index - 1)?.id, doc.chapters.getOrNull(index + 1)?.id, c.paragraphs, c.paragraphs) to true
        } else {
            val generation = store.cacheGeneration.value
            val cached = store.cachedChapter(ref, id)
            if(cached != null && !forceNetwork) return@withContext cached to true
            try { api.chapter(ref, id).also { chapter -> runCatching { store.withCacheGeneration(generation) { store.cacheChapter(ref, id, chapter) } } } to false }
            catch(e: IOException) { cached?.let { it to true } ?: throw e }
        }
    }
    suspend fun cloudMutation(method: String, path: String, body: String? = null, contentType: String = "application/json") {
        val account = session.profile.value?.username ?: throw ApiException(401, "请先登录")
        try { api.request(method, path, body, contentType = contentType) }
        catch(e: IOException) {
            if(e is ApiException || method !in listOf("PUT", "DELETE")) throw e
            store.update { current -> current.copy(pending = current.pending.filterNot { it.path == path && it.account == account } + PendingAction(UUID.randomUUID().toString(), account, method, path, body, contentType)) }
            message("网络不可用，操作已加入待同步列表")
        }
    }
    fun syncPending() = action("同步完成") {
        val account = session.profile.value?.username ?: throw ApiException(401, "请先登录")
        for(item in store.state.value.pending.filter { it.account == account }) {
            api.request(item.method, item.path, item.body, contentType = item.contentType)
            store.update { it.copy(pending = it.pending.filterNot { p -> p.id == item.id }) }
        }
    }
}
