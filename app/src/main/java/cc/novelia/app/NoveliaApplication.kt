package cc.novelia.app

import android.app.Application
import cc.novelia.app.data.auth.Session
import cc.novelia.app.data.catalog.KeywordStore
import cc.novelia.app.data.catalog.ClipboardLinkHistory
import cc.novelia.app.data.model.User
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.network.EchTransport
import cc.novelia.app.data.network.echRedirects
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.data.updates.UpdateWorker
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

/**
 * 应用级服务的装配入口，书库、会话、API 和图片加载器在各页面之间复用。
 * [initialization] 是进入主界面的初始化屏障：磁盘读取在 IO 作用域中执行，Activity 等待
 * 完成后再观察书库。后台维护使用 SupervisorJob，单个维护任务失败不会取消其他任务。
 * 此处只持有应用级对象，页面选择、弹窗及导航回调由界面自己的生命周期管理。
 */
class NoveliaApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { LocalStore(this) }
    val ech by lazy { EchTransport(this) }
    private val httpTransport by lazy { ech.client() }
    val session by lazy { Session(this, client = httpTransport) }
    val keywords by lazy { KeywordStore(this) { store.state.value.keywordLimit } }
    internal val clipboardLinkHistory = ClipboardLinkHistory()
    val metadataCache get() = store.metadataCache
    val api by lazy { NoveliaApi(session, transport = httpTransport, onMutation = { metadataCache.invalidate(it) }, onKeywords = { tags -> applicationScope.launch { keywords.observe(tags) } }) }
    val initialization by lazy { applicationScope.async { store; session; Unit } }

    override fun onCreate() {
        super.onCreate()
        initialization
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
                session.profile.map { it?.let { user -> user.username to user.expiresAt } }.distinctUntilChanged()) { pending, login -> pending to login?.first }
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

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { api.transport.newBuilder().echRedirects(true).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
}
