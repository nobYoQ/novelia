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
    var afterLogin: (() -> Unit)? = null
    fun go(route: String) { nav.navigate(route) { launchSingleTop = true } }
    fun back() { nav.popBackStack() }
    fun book(ref: BookRef) { if(ref.isLocal) read(ref, store.state.value.positions[ref.key]?.chapterId ?: store.document(ref.id).chapters.first().id) else go("book/${ref.provider}/${ref.id}") }
    fun read(ref: BookRef, chapter: String) = go("reader/${ref.provider}/${ref.id}/${Uri.encode(chapter)}")
    fun openLink(text: String) {
        when(val link = BookLinks.parse(text)) {
            is SiteLink.Book -> if(link.chapterId != null) read(link.ref, link.chapterId) else book(link.ref)
            is SiteLink.Post -> go("article/${link.id}")
            null -> go("discover?query=${Uri.encode(text)}")
        }
    }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun action(success: String? = null, block: suspend () -> Unit) { scope.launch {
        try { block(); success?.let { snackbar.showSnackbar(it) } }
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
    suspend inline fun <reified T> detail(path: String): T = withContext(Dispatchers.IO) {
        val file = File(store.metadataDir, hashName("${session.profile.value?.username ?: "guest"}:$path") + ".json")
        try { val raw = api.request("GET", path); val parsed = appJson.decodeFromString<T>(raw); file.writeText(raw); parsed }
        catch(e: IOException) { if(e is ApiException || !file.exists()) throw e else appJson.decodeFromString<T>(file.readText()) }
    }
    suspend fun chapter(ref: BookRef, id: String, forceNetwork: Boolean = false): Pair<Chapter, Boolean> = withContext(Dispatchers.IO) {
        if(ref.isLocal) {
            val doc = store.document(ref.id); val index = doc.chapters.indexOfFirst { it.id == id }.coerceAtLeast(0); val c = doc.chapters[index]
            Chapter(c.title, c.title, doc.name, doc.name, doc.chapters.getOrNull(index - 1)?.id, doc.chapters.getOrNull(index + 1)?.id, c.paragraphs, c.paragraphs) to true
        } else {
            val cached = store.cachedChapter(ref, id)
            if(cached != null && !forceNetwork) return@withContext cached to true
            try { api.chapter(ref, id).also { store.cacheChapter(ref, id, it) } to false }
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
