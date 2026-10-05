package cc.novelia.app

import cc.novelia.app.data.network.ForumAccountApi
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.network.ForumApi
import cc.novelia.app.data.network.ForumCommunityRulesRepository
import cc.novelia.app.data.cache.MetadataCache
import android.app.Application
import cc.novelia.app.data.auth.Session
import cc.novelia.app.data.catalog.KeywordStore
import cc.novelia.app.data.catalog.ClipboardLinkHistory
import cc.novelia.app.data.model.User
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.network.EchTransport
import cc.novelia.app.data.network.BookSources
import cc.novelia.app.data.network.BookSourceInterceptor
import cc.novelia.app.data.network.echRedirects
import cc.novelia.app.data.network.echCallTimeout
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.data.webdav.WebDavConfigStore
import cc.novelia.app.data.webdav.WebDavSyncManager
import cc.novelia.app.data.webdav.WebDavSyncWorker
import cc.novelia.app.data.webdav.automaticallySyncable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.launcher.LauncherIconManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * 应用级服务的装配入口，书库、会话、API 和图片加载器在各页面之间复用。
 * [initialization] 是进入主界面的初始化屏障：磁盘读取在 IO 作用域中执行，Activity 等待
 * 完成后再观察书库。后台维护使用 SupervisorJob，单个维护任务失败不会取消其他任务。
 * 此处只持有应用级对象，页面选择、弹窗及导航回调由界面自己的生命周期管理。
 */
class NoveliaApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { LocalStore(this) { webDavConfig.config.value.syncDevicePreferences } }
    val ech by lazy { EchTransport(this) }
    val bookSources by lazy { BookSources.load(this) }
    private val forumTransport by lazy { ech.client() }
    private val httpTransport by lazy { forumTransport.newBuilder().apply {
        interceptors().add(0, BookSourceInterceptor(bookSources))
    }.echCallTimeout().build() }
    val session by lazy { Session(this, client = httpTransport, sources = bookSources) }
    val forumSession by lazy { Session(this, client = httpTransport, sources = bookSources, target = AuthTarget.FORUM) }
    val keywords by lazy { KeywordStore(this) { store.state.value.keywordLimit } }
    val webDavConfig by lazy { WebDavConfigStore(this) }
    val webDav by lazy { WebDavSyncManager(this) }
    @Volatile private var webDavForeground = false
    private var webDavPoll: Job? = null
    private var webDavEdit: Job? = null
    internal val clipboardLinkHistory = ClipboardLinkHistory()
    val metadataCache get() = store.metadataCache
    val api by lazy { NoveliaApi(session, transport = httpTransport, onMutation = { metadataCache.invalidate(it) }, onKeywords = { tags -> applicationScope.launch { keywords.observe(tags) } }) }
    val forumApi by lazy { ForumApi(NoveliaApi(forumSession, ForumApi.BASE_URL, httpTransport)) }
    val forumAccountApi by lazy { ForumAccountApi(NoveliaApi(forumSession, ForumAccountApi.BASE_URL, httpTransport)) }
    val forumCommunityRules by lazy { ForumCommunityRulesRepository(
        MetadataCache(File(filesDir, "forum-community-rules"), 512 * 1024L),
        NoveliaApi(null, "https://forum.novelia.cc/", forumTransport),
        NoveliaApi(null, ForumCommunityRulesRepository.SOURCE_BASE, forumTransport)
    ) }
    val initialization by lazy { applicationScope.async { store; session; Unit } }
    internal val launcherIcons by lazy { LauncherIconManager(this) }

    override fun onCreate() {
        super.onCreate()
        launcherIcons
        initialization
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                webDavForeground = true
                webDavPoll?.cancel()
                webDavPoll = applicationScope.launch {
                    initialization.await()
                    while(webDavForeground) {
                        syncWebDavIfAllowed()
                        delay(180_000)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                webDavForeground = false
                webDavPoll?.cancel()
                webDavEdit?.cancel()
                persistState()
            }
        })
        applicationScope.launch {
            initialization.await()
            webDavConfig.config.collectLatest { configuration ->
                WebDavSyncWorker.configure(this@NoveliaApplication, configuration)
                if(configuration.automaticallySyncable()) {
                    syncWebDavIfAllowed()
                }
            }
        }
        applicationScope.launch {
            initialization.await()
            delay(750)
            combine(store.state.map { it.syncReplica.clock }.distinctUntilChanged(),
                keywords.state.map { it.syncReplica.clock }.distinctUntilChanged(), webDavConfig.config) { _, _, configuration -> configuration }
                .collect { configuration ->
                    webDav.refreshPendingStatus()
                    if(configuration.automaticallySyncable() && webDav.hasPending()) {
                        // 队列任务只保留一个，连续翻页不会无限推迟首次提交。
                        try {
                            store.flush()
                            if(cc.novelia.app.data.webdav.SyncDomain.KEYWORDS in configuration.selected) keywords.flush()
                            WebDavSyncWorker.enqueue(this@NoveliaApplication, configuration)
                            if(webDavForeground && webDavEdit?.isActive != true) webDavEdit = applicationScope.launch {
                                delay(10_000)
                                syncWebDavIfAllowed()
                            }
                        } catch(cancelled: CancellationException) { throw cancelled }
                        catch(_: Exception) { /* 存储错误已由各存储公开；后台周期任务会再检查。 */ }
                    }
                }
        }
        applicationScope.launch {
            initialization.await()
            // 首帧无需加载和合并标签翻译目录，延后处理以减轻启动负担。
            delay(500)
            keywords.observe(store.state.value.books.flatMap { it.book.tags })
        }
        applicationScope.launch {
            initialization.await()
            cc.novelia.app.files.DownloadFiles.cleanup(store.downloadsDir)
            // 回收已停用图片识别工具遗留的可选模型下载。
            // 用户编辑的文字仍保留在草稿中，可通过普通文本工具继续使用。
            runCatching { cc.novelia.app.files.removeRetiredModels(noBackupFilesDir) }
        }
        applicationScope.launch {
            initialization.await()
            store.state.map { it.autoSync }.distinctUntilChanged().collect { enabled ->
                CloudSyncWorker.configure(this@NoveliaApplication, enabled)
            }
        }
        applicationScope.launch {
            initialization.await()
            store.state.map { it.updateNotifications }.distinctUntilChanged().collect { enabled ->
                UpdateWorker.schedule(this@NoveliaApplication, enabled)
            }
        }
        applicationScope.launch {
            initialization.await()
            // 只观察影响调度的字段，避免每次保存阅读位置都重新安排后台同步。
            // collectLatest 会取消旧一轮等待；切换账号或队列变化后必须基于最新状态判断。
            combine(store.state.map { it.autoSync to it.pending.map { action -> action.account to action.id } }.distinctUntilChanged(),
                session.profile.map { it?.let { user -> user.username to user.expiresAt } }.distinctUntilChanged(),
                bookSources.state) { pending, login, _ -> pending to login?.first }
                .collectLatest { (pending, account) ->
                    // 前台成功的写入意图会很快移出队列，只为仍待处理的意图调度后台任务。
                    delay(750)
                    if(pending.first && account != null && pending.second.any { it.first == account } && store.recoveryIssue.value == null) {
                        while(true) {
                            try {
                                // WorkManager 可能在进程重启后执行，因此先确保待同步意图已写入磁盘。
                                store.flush()
                                CloudSyncWorker.enqueue(this@NoveliaApplication, account)
                                break
                            } catch(error: CancellationException) { throw error }
                            catch(_: Exception) { delay(2_000) }
                        }
                    }
                }
        }
    }

    /** 请求异步刷新书库和标签；生命周期回调不阻塞主线程，也不把返回视为已落盘。 */
    fun persistState() {
        applicationScope.launch {
            initialization.await()
            // LocalStore 向界面发布可恢复的保存错误，并在后续写入时重试。
            runCatching { store.flush() }
            runCatching { keywords.flush() }
        }
    }

    private suspend fun syncWebDavIfAllowed() {
        val configuration = webDavConfig.config.value
        if(!configuration.automaticallySyncable()) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return
        if(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            configuration.wifiOnly && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        try { webDav.synchronize(manual = false, expectedGeneration = configuration.generation) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { /* 同步界面提供错误，Worker 负责可恢复失败的退避。 */ }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { api.transport.newBuilder().echRedirects(true).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
}
