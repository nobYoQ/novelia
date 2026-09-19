package cc.novelia.app

import android.app.Application
import cc.novelia.app.data.LocalStore
import cc.novelia.app.data.MetadataCache
import cc.novelia.app.data.NoveliaApi
import cc.novelia.app.data.Session
import cc.novelia.app.data.KeywordStore
import cc.novelia.app.data.CloudSyncWorker
import cc.novelia.app.data.UpdateWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

class NoveliaApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { LocalStore(this) }
    val session by lazy { Session(this) }
    val keywords by lazy { KeywordStore(this) }
    val metadataCache by lazy { MetadataCache(store.metadataDir) }
    val api by lazy { NoveliaApi(session, onMutation = { metadataCache.invalidate(it) }, onKeywords = keywords::observe) }
    val initialization by lazy { applicationScope.async { store; session; keywords.observe(store.state.value.books.flatMap { it.book.tags }); Unit } }

    override fun onCreate() {
        super.onCreate()
        initialization
        applicationScope.launch {
            initialization.await()
            cc.novelia.app.files.DownloadFiles.cleanup(store.downloadsDir)
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
            combine(store.state.map { it.autoSync to it.pending.map { action -> action.account to action.id } }.distinctUntilChanged(),
                session.profile.map { it?.let { user -> user.username to user.expiresAt } }.distinctUntilChanged()) { pending, login -> pending to login?.first }
                .collectLatest { (pending, account) ->
                    // Successful foreground mutations disappear quickly; schedule only durable intents.
                    delay(750)
                    if(pending.first && account != null && pending.second.any { it.first == account } && store.recoveryIssue.value == null) {
                        while(true) {
                            try {
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

    fun persistState() {
        applicationScope.launch {
            initialization.await()
            // LocalStore publishes a recoverable error for the UI and retries on subsequent writes.
            runCatching { store.flush() }
            runCatching { keywords.flush() }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { api.transport.newBuilder().followRedirects(true).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
}
