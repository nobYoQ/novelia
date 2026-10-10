package cc.novelia.app.ui.navigation

import cc.novelia.app.data.model.Article
import cc.novelia.app.data.catalog.ForumLinks
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.catalog.NovelFilterDetails
import cc.novelia.app.data.catalog.NovelFilterMetadataCache
import cc.novelia.app.data.catalog.NovelFilterPageCache
import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.toReaderChapter
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.updates.withBookUpdate
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.library.withCloudFavoriteLocalCopy
import cc.novelia.app.data.library.CloudBookMetadataLoader
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.sync.pendingBookKey
import cc.novelia.app.data.sync.syncFailureMessage
import cc.novelia.app.data.sync.synchronizePending
import cc.novelia.app.data.sync.updateCloudPending
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerSnackbarVisuals
import cc.novelia.app.ui.reader.ReaderTocState
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

/**
 * 页面共享的导航和业务调用入口，将 Compose 事件连接到应用级服务。
 * 由主界面 remember 创建，并非 ViewModel；afterLogin 回调、弹窗和正文交接只保留在内存中。
 * 需要跨重建恢复的收藏登录意图由 LoginContinuation 写入登录导航项，而非依赖此处回调。
 * scope 属于当前组合生命周期，action 中的耗时任务必须自行切换 IO/计算调度器。
 */
class AppController(val app: NoveliaApplication, val nav: NavHostController, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    val store get() = app.store
    val api get() = app.api
    val session get() = app.session
    val forumApi get() = app.forumApi
    val forumAccountApi get() = app.forumAccountApi
    val forumSession get() = app.forumSession
    val metadataCache get() = app.metadataCache
    var afterLogin: (() -> Unit)? = null
    var pendingFavorite by mutableStateOf<BookCard?>(null)
    var pendingFavoriteCloud by mutableStateOf(false)
    private var celebration: Job? = null
    /** 进入书籍详情覆盖发现页时，保留最近一页的发现结果。 */
    internal var discoverPage: Pair<Any, Page<BookCard>>? = null
    internal var filteredDiscoverPage: Pair<Any, cc.novelia.app.data.catalog.FilteredNovelBatch>? = null
    private val filterMetadataCache = NovelFilterMetadataCache(metadataCache)
    private val filterPageCache = NovelFilterPageCache()

    internal suspend fun filteredWebList(page: Int, query: String, provider: String, type: Int, level: Int,
        translate: Int, sort: Int, forceNetwork: Boolean = false): Page<BookCard> {
        val binding = session.capture()
        val generation = store.cacheGeneration.value
        val mutation = api.lastMutationAt
        val key = listOf(binding, generation, mutation, page, query, provider, type, level, translate, sort)
        if(!forceNetwork) filterPageCache.get(key)?.let { session.ensureCurrent(binding); return it }
        val fetchedAt = System.currentTimeMillis()
        val remote = api.webList(page, query, provider, type, level, translate, sort)
        session.ensureCurrent(binding)
        return Page(remote.pageNumber, remote.items.map { it.card() }).also {
            if(generation == store.cacheGeneration.value && mutation == api.lastMutationAt) filterPageCache.put(key, it, fetchedAt)
        }
    }

    /** 小字段缓存优先，来源列表发生更新才重新补查；显式重新查找可强制刷新。 */
    internal suspend fun filterBookMetadata(book: BookCard, forceNetwork: Boolean = false): BookCard = withContext(Dispatchers.IO) {
        val binding = session.capture()
        val account = binding.cacheAccount
        val generation = store.cacheGeneration.value
        val mutation = api.lastMutationAt
        val cached = filterMetadataCache.read(book.ref, account, mutation)
        if(!forceNetwork && cached?.reusable(book) == true) {
            session.ensureCurrent(binding)
            return@withContext cached.metadata.details.applyTo(book)
        }
        val path = "novel/${book.ref.key}"
        val detailKey = hashName("$account:$path")
        // 已知列表发生变化时，不拿更新前的完整详情重新生成看似新鲜的字数缓存。
        val existing = if(!forceNetwork && (cached == null || cached.matchesSource(book)))
            metadataCache.readSnapshot(detailKey, maxAgeMillis = 5 * 60_000L, newerThan = mutation) else null
        try {
            val fetchedAt = System.currentTimeMillis()
            val raw = existing ?: MetadataCache.Snapshot(api.request("GET", path, binding = binding), fetchedAt)
            val details = appJson.decodeFromString<NovelFilterDetails>(raw.text)
            session.ensureCurrent(binding)
            if(mutation == api.lastMutationAt) runCatching {
                store.withCacheGeneration(generation) {
                    if(existing == null) metadataCache.write(detailKey, raw.text, raw.fetchedAt)
                    filterMetadataCache.write(book, account, details, raw.fetchedAt)
                }
            }
            details.applyTo(book)
        } catch(error: IOException) {
            session.ensureCurrent(binding)
            if(error is ApiException || forceNetwork || cached?.matchesSource(book) != true) throw error
            cached.metadata.details.applyTo(book)
        }
    }
    /** 一次性正文交接，附带账号绑定和缓存代次，避免跨章导航后再次请求或接收过期内容。 */
    internal data class ReaderHandoff(val ref: BookRef, val id: String, val value: Pair<Chapter, Boolean>, val binding: SessionBinding, val generation: Long)
    private var readerHandoff: ReaderHandoff? = null
    private data class ReaderTocKey(val ref: BookRef, val binding: SessionBinding, val generation: Long)
    private var readerToc: Pair<ReaderTocKey, ReaderTocState>? = null

    /** 只保留最近一本目录，身份/书源或缓存代次改变时隔离旧结果。 */
    @Synchronized internal fun readerToc(ref: BookRef): ReaderTocState {
        val key = ReaderTocKey(ref, session.capture(), store.cacheGeneration.value)
        readerToc?.takeIf { it.first == key }?.let { return it.second }
        return ReaderTocState { forceNetwork ->
            withContext(Dispatchers.IO) {
                session.ensureCurrent(key.binding)
                val toc = if(ref.isLocal) store.documentIndex(ref.id).chapters.map { TocItem(it.title, it.title, it.id) }
                    else detail<WebDetail>("novel/${ref.key}", forceNetwork).toc
                session.ensureCurrent(key.binding)
                check(key.generation == store.cacheGeneration.value) { "缓存已更新，请重新加载目录" }
                toc
            }
        }.also { readerToc = key to it }
    }
    private val cloudBookMetadata = CloudBookMetadataLoader(session) { ref -> detail<WebDetail>("novel/${ref.key}") }

    /** 仅云端收藏也需要章节元数据，补取详情时不隐式加入本地书架。 */
    suspend fun refreshCloudReading(book: BookCard): BookCard {
        val binding = session.capture()
        val card = cloudBookMetadata.load(book)
        session.ensureCurrent(binding)
        store.update { it.withCloudReadingMetadata(listOf(card), binding.account) }
        return card
    }
    // 同一路由的不同参数仍需独立的历史记录；
    // 根标签切换的 singleTop/restoreState 由 MainActivity 单独处理。
    fun go(route: String, replaceTop: Boolean = false) { nav.navigate(route) { launchSingleTop = replaceTop } }
    fun back() { nav.popBackStack() }
    fun book(ref: BookRef) {
        if (ref.isLocal) action {
            val chapter = store.state.value.positions[ref.key]?.chapterId ?: withContext(Dispatchers.IO) {
                store.documentIndex(ref.id).chapters.firstOrNull()?.id ?: error("这本小说没有可阅读的章节")
            }
            read(ref, chapter)
        } else go("book/${ref.provider}/${ref.id}")
    }
    fun read(ref: BookRef, chapter: String) {
        // 从书架/详情重新开始阅读时重新检查目录；连续切章走下面的交接入口。
        synchronized(this) { readerToc = null }
        goReader(ref, chapter)
    }
    private fun goReader(ref: BookRef, chapter: String) = go("reader/${ref.provider}/${ref.id}/${Uri.encode(chapter)}")
    internal suspend fun prepareReaderChapter(ref: BookRef, id: String): ReaderHandoff {
        val binding = session.capture()
        val generation = store.cacheGeneration.value
        val value = chapter(ref, id)
        session.ensureCurrent(binding)
        return ReaderHandoff(ref, id, value, binding, generation)
    }
    /** 离开当前阅读器前先校验目标章，已加载正文只交接一次，避免重复请求。 */
    internal fun readPreparedChapter(prepared: ReaderHandoff) {
        session.ensureCurrent(prepared.binding)
        check(prepared.generation == store.cacheGeneration.value) { "缓存已更新，请重新加载章节" }
        synchronized(this) { readerHandoff = prepared }
        nav.popBackStack()
        goReader(prepared.ref, prepared.id)
    }
    fun openLink(text: String) {
        when(val link = BookLinks.parse(text)) {
            is SiteLink.Book -> if(link.chapterId != null) read(link.ref, link.chapterId) else book(link.ref)
            is SiteLink.Post -> go("article/${link.id}")
            null -> {
                val url = text.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { MarkdownLinks.resolve(it) }
                if(url != null && MarkdownLinks.isInternal(url)) openMarkdownLink(url) else go("discover?query=${Uri.encode(text)}")
            }
        }
    }
    fun openMarkdownLink(destination: String, documentUrl: String? = null) {
        val url = MarkdownLinks.resolve(destination, documentUrl) ?: run { message("不支持此链接类型"); return }
        val route = MarkdownLinks.nativeRoute(url)
        when {
            route != null -> go(route)
            MarkdownLinks.isInternal(url) -> go("web?url=${Uri.encode(url)}")
            else -> external(url)
        }
    }
    fun message(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) { scope.launch {
        if(snackbar.showSnackbar(text, actionLabel = actionLabel, withDismissAction = actionLabel != null,
                duration = if(actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onAction()
    } }
    fun celebrate(text: String, sticker: MidoriSticker, actionLabel: String? = null, onAction: () -> Unit = {}) {
        celebration?.cancel()
        celebration = scope.launch {
            if(snackbar.showSnackbar(StickerSnackbarVisuals(text, sticker, actionLabel = actionLabel,
                    duration = if(actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long)) == SnackbarResult.ActionPerformed) onAction()
        }
    }
    fun action(success: String? = null, sticker: MidoriSticker? = null, block: suspend () -> Unit) { scope.launch(Dispatchers.Main.immediate) {
        try { block(); success?.let { if(sticker != null) celebrate(it, sticker) else snackbar.showSnackbar(it) } }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { snackbar.showSnackbar(e.friendlyMessage()) }
    } }
    fun requireLogin(action: () -> Unit) { if(session.profile.value == null) { afterLogin = action; go("login") } else action() }
    fun requireForumLogin(action: () -> Unit) { if(forumSession.profile.value == null) { afterLogin = action; go("forum-login") } else action() }
    suspend fun article(id: String): Article {
        val auth = if(ForumLinks.postId(id) != null) forumSession else session
        val binding = auth.capture()
        val article = (ForumLinks.postId(id)?.let { forumApi.post(it).article(forumApi.categories()) }
            ?: api.get<Article>("article/$id")).copy(id = id)
        auth.ensureCurrent(binding)
        return article
    }
    fun external(url: String) {
        val uri = Uri.parse(url)
        if(uri.scheme !in listOf("https", "http")) { message("不支持此链接类型"); return }
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { message("设备没有可用的浏览器") }
    }
    fun share(text: String) { app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    /** 完整详情确认增量；缓存沿用原始获取时间，重复显示不重复提醒。 */
    fun observeBookUpdate(path: String, detail: Any?, observedAt: Long) {
        val book = when {
            detail is WebDetail && path.startsWith("novel/") -> detail.card(BookRef.fromKey(path.removePrefix("novel/")), session.capture().account)
            detail is WenkuDetail && path.startsWith("wenku/") -> detail.card(BookRef("wenku", path.removePrefix("wenku/")))
            else -> return
        }
        store.update { it.withBookUpdate(book, observedAt) }
    }
    /**
     * 读取账号隔离的详情：优先使用五分钟内的缓存，再请求网络。
     * 普通 IO 失败可回退旧缓存，HTTP 业务错误直接抛出；forceNetwork 跳过首次缓存命中，
     * 但仍保留 IO 失败回退。响应只有在写操作时间和缓存代次未变化时才允许缓存。
     */
    suspend inline fun <reified T> detail(path: String, forceNetwork: Boolean = false): T = withContext(Dispatchers.IO) {
        val binding = session.capture()
        val account = binding.account ?: "guest"
        val key = hashName("${binding.cacheAccount}:$path")
        val mutation = api.lastMutationAt
        val generation = store.cacheGeneration.value
        if (!forceNetwork) {
            metadataCache.readSnapshot(key, maxAgeMillis = 5 * 60_000L, newerThan = mutation)?.let { cached ->
                runCatching { appJson.decodeFromString<T>(cached.text) }.getOrNull()?.let {
                    session.ensureCurrent(binding)
                    api.observeKeywords(it)
                    observeBookUpdate(path, it, cached.fetchedAt)
                    return@withContext it
                }
            }
        }
        try {
            val fetchedAt = System.currentTimeMillis()
            val raw = api.request("GET", path, binding = binding)
            val parsed = appJson.decodeFromString<T>(raw)
            api.observeKeywords(parsed)
            if (mutation == api.lastMutationAt && generation == store.cacheGeneration.value && account == (session.profile.value?.username ?: "guest")) {
                session.ensureCurrent(binding)
                observeBookUpdate(path, parsed, fetchedAt)
                runCatching { store.withCacheGeneration(generation) { metadataCache.write(key, raw, fetchedAt) } }
            }
            parsed
        } catch (e: IOException) {
            if (e is ApiException) throw e
            val cached = metadataCache.readSnapshot(key) ?: throw e
            val parsed = runCatching { appJson.decodeFromString<T>(cached.text) }.getOrElse { throw e }
            session.ensureCurrent(binding)
            api.observeKeywords(parsed)
            observeBookUpdate(path, parsed, cached.fetchedAt)
            parsed
        }
    }
    /**
     * 统一读取本地文档和网络章节；返回值第二项为 true 表示内容来自本地文档或章节缓存。
     * 优先消费跨章加载的一次性交接结果，避免导航后重复请求。网络强制刷新失败会直接报错，
     * 不能用旧缓存伪装成刷新成功；普通读取则优先使用已缓存的章节。
     */
    suspend fun chapter(ref: BookRef, id: String, forceNetwork: Boolean = false): Pair<Chapter, Boolean> = withContext(Dispatchers.IO) {
        val handoff = synchronized(this@AppController) {
            readerHandoff?.takeIf { it.ref == ref && it.id == id }?.also { readerHandoff = null }
        }
        if(!forceNetwork && handoff != null && handoff.binding == session.capture() && handoff.generation == store.cacheGeneration.value)
            return@withContext handoff.value
        if(ref.isLocal) {
            val doc = store.documentIndex(ref.id); val index = doc.chapters.indexOfFirst { it.id == id }.coerceAtLeast(0)
            val c = store.documentChapter(ref.id, doc.chapters.getOrNull(index)?.id ?: error("这本小说没有可阅读的章节"))
            c.toReaderChapter(doc.name, doc.chapters.getOrNull(index - 1)?.id, doc.chapters.getOrNull(index + 1)?.id) to true
        } else {
            val binding = session.capture()
            val generation = store.cacheGeneration.value
            val cached = store.cachedChapter(ref, id)
            if(cached != null && !forceNetwork) return@withContext cached to true
            try {
                val chapter = store.chapterRequests.load(api, session, binding, generation, ref, id)
                session.ensureCurrent(binding)
                chapter to false
            } catch(e: IOException) {
                session.ensureCurrent(binding)
                if(forceNetwork || e is ApiException) throw e
                cached?.let { it to true } ?: throw e
            }
        }
    }
    /**
     * 提交绑定当前账号的云端意图，并把队列变换和失败信息接入 LocalStore。
     * 返回 true 表示仍待同步，不能当作远端已更新；返回 false 才表示此次发送成功。
     */
    suspend fun cloudMutation(method: String, path: String, body: String? = null, contentType: String = "application/json", notifyQueued: Boolean = true): Boolean {
        val binding = session.capture()
        val account = binding.account ?: throw ApiException(401, "请先登录")
        val action = PendingAction(UUID.randomUUID().toString(), account, method, path, body, contentType)
        val queued = api.cloudMutations.submit(action, { transform -> store.update { it.updateCloudPending(transform) } }, { session.ensureCurrent(binding) }, onQueuedFailure = { error ->
            store.update { state ->
                val status = state.syncStatus[account] ?: CloudSyncStatus()
                state.copy(syncStatus = state.syncStatus + (account to status.copy(failures = status.failures + (action.id to syncFailureMessage(error)))))
            }
        }) { item ->
            api.request(item.method, item.path, item.body, contentType = item.contentType, binding = binding)
        }
        if (!queued) store.update { state ->
            val status = state.syncStatus[account] ?: CloudSyncStatus()
            state.copy(syncStatus = state.syncStatus + (account to status.copy(lastSuccessAt = System.currentTimeMillis(), requiresLogin = false)))
        }
        if (queued && notifyQueued) message("操作已保存，等待同步")
        return queued
    }
    /** 显式云端收藏操作可同时为同一本书创建可选的本地副本。 */
    suspend fun addCloudFavorite(book: BookCard, folderId: String): Boolean {
        val binding = session.capture()
        val path = if(book.ref.isWenku) "user/favored-wenku/$folderId/${book.ref.id}"
            else "user/favored-web/$folderId/${book.ref.key}"
        val queued = cloudMutation("PUT", path)
        session.ensureCurrent(binding)
        store.update { it.withCloudFavoriteLocalCopy(book) }
        return queued
    }
    fun syncBook(ref: BookRef) {
        val binding = session.capture()
        action {
            session.ensureCurrent(binding)
            synchronizePending(app, manual = true, bookKey = ref.key, binding = binding)
            session.ensureCurrent(binding)
            val remaining = store.state.value.pending.count { it.account == binding.account && pendingBookKey(it) == ref.key }
            message(if (remaining == 0) "本书同步完成" else "本书仍有 $remaining 项待同步，请查看书目下方的原因")
        }
    }
    fun syncPending() {
        val binding = session.capture()
        action {
            session.ensureCurrent(binding)
            val account = binding.account ?: throw ApiException(401, "请先登录")
            val result = synchronizePending(app, manual = true, binding = binding)
            session.ensureCurrent(binding)
            val remaining = store.state.value.pending.count { it.account == account }
            message(if(remaining == 0) "同步完成" else "已同步 ${result.completed} 项，仍有 $remaining 项待处理，可在网络与同步中查看原因")
        }
    }
}
