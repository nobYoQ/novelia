package cc.novelia.app

import android.app.Application
import cc.novelia.app.data.LocalStore
import cc.novelia.app.data.MetadataCache
import cc.novelia.app.data.NoveliaApi
import cc.novelia.app.data.Session
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

class NoveliaApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { LocalStore(this) }
    val session by lazy { Session(this) }
    val metadataCache by lazy { MetadataCache(store.metadataDir) }
    val api by lazy { NoveliaApi(session, onMutation = { metadataCache.invalidate(it) }) }
    val initialization by lazy { applicationScope.async { store; session; Unit } }

    override fun onCreate() {
        super.onCreate()
        initialization
        applicationScope.launch {
            initialization.await()
            cc.novelia.app.files.DownloadFiles.cleanup(store.downloadsDir)
        }
    }

    fun persistState() {
        applicationScope.launch {
            initialization.await()
            // LocalStore publishes a recoverable error for the UI and retries on subsequent writes.
            runCatching { store.flush() }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { api.transport.newBuilder().followRedirects(true).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "images")).maxSizeBytes(128L * 1024 * 1024).build() }
        .build()
}
