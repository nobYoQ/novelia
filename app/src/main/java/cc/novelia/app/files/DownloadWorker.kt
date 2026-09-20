package cc.novelia.app.files

import android.content.Context
import androidx.work.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.updates.AppNotifications
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as NoveliaApplication
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val workId = this@DownloadWorker.id.toString()
        val entry = DownloadFiles.withTaskLock(app.store.downloadsDir, id) {
            ensureActive()
            val saved = app.store.state.value.downloads.find { it.id == id } ?: return@withTaskLock null
            if (isStopped || saved.status == "已暂停" || (saved.workId != null && saved.workId != workId)) return@withTaskLock null
            // Existing installs have no workId. Claim only while their current worker is active.
            val current = saved.copy(workId = workId)
            app.store.update { state -> state.copy(downloads = state.downloads.map { if (it.id == id) current else it }) }
            current
        } ?: return@withContext Result.failure()
        val owner = inputData.getString("owner")
        val binding = app.session.capture()
        if(owner != null && owner != binding.account) { update(app, id, "需要登录", error = "请登录创建此下载的账号后重试"); app.store.flush(); return@withContext Result.failure() }
        val partial = DownloadFiles.acquire(app.store.downloadsDir, id, this@DownloadWorker.id.toString()); val destination = File(app.store.downloadsDir, entry.fileName)
        try {
            update(app, id, "下载中", 0)
            val client = app.api.transport.newBuilder().followRedirects(true).readTimeout(120, TimeUnit.SECONDS).build()
            val parsed = entry.url.toHttpUrl(); require(parsed.scheme == "https" && parsed.host == "n.novelia.cc")
            val request = Request.Builder().url(parsed).build()
            val workContext = coroutineContext
            app.api.withAuthenticatedResponse(request, binding, client) { response ->
                if(!response.isSuccessful) error("下载失败（${response.code}），请检查译文是否完整或重新登录")
                val body = response.body ?: error("服务器没有返回文件")
                require(!body.contentType().toString().startsWith("text/html")) { "服务器返回了网页，请稍后重试" }
                val expected = body.contentLength(); require(expected <= 512L * 1024 * 1024) { "文件超过 512 MB" }
                var total = 0L; var previousProgress = -1; var lastProgressAt = 0L
                partial.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    while(true) {
                        workContext.ensureActive()
                        app.session.ensureCurrent(binding)
                        if(isStopped) throw CancellationException("Download stopped")
                        val count = input.read(buffer); if(count < 0) break
                        total += count; require(total <= 512L * 1024 * 1024) { "文件超过 512 MB" }; output.write(buffer, 0, count)
                        val progress = if(expected > 0) (total * 100 / expected).toInt() else 0
                        val now = android.os.SystemClock.elapsedRealtime()
                        if(progress != previousProgress && now - lastProgressAt >= 250) { previousProgress = progress; lastProgressAt = now; update(app, id, "下载中", progress, active = { workContext[kotlinx.coroutines.Job]?.isActive == true }) }
                    }
                } }
                if(expected >= 0 && total != expected) error("下载不完整，请重试")
                if(total == 0L) error("下载内容为空")
            }
            val committed = DownloadFiles.commit(app.store.downloadsDir, id, partial, destination, {
                ensureActive()
                app.session.ensureCurrent(binding)
                !isStopped && app.store.state.value.downloads.any { it.id == id && it.workId == workId && it.fileName == entry.fileName && it.status != "已暂停" }
            }) {
                update(app, id, "已完成", 100)
                cc.novelia.app.data.updates.AppNotifications.show(app, id.hashCode(), "下载已完成", entry.title)
            }
            if (!committed) return@withContext Result.failure()
            try { app.store.flush() } catch (_: java.io.IOException) { return@withContext Result.retry() }
            Result.success()
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            if(isStopped) throw CancellationException("Download stopped", e)
            update(app, id, if(e is ApiException && e.status == 401) "需要登录" else "失败",
                error = if(e is ApiException) e.message else if(e is java.io.IOException) "网络中断，点击重试重新下载" else e.message?.take(160) ?: "下载失败")
            try { app.store.flush() } catch (_: java.io.IOException) { return@withContext Result.retry() }
            Result.failure()
        } finally {
            DownloadFiles.release(partial)
            withContext(NonCancellable) { runCatching { app.store.flush() } }
        }
    }
    private fun update(app: NoveliaApplication, id: String, status: String, progress: Int? = null, error: String? = null, active: () -> Boolean = { true }) {
        app.store.update { state ->
            if (isStopped || !active()) state else state.copy(downloads = state.downloads.map { d ->
                if(d.id == id && d.workId == this@DownloadWorker.id.toString() && !(d.status == "已暂停" && status == "下载中")) d.copy(status = status, progress = progress ?: d.progress, error = error) else d
            })
        }
    }
    companion object {
        suspend fun remove(app: NoveliaApplication, id: String) = withContext(Dispatchers.IO) {
            DownloadFiles.withTaskLock(app.store.downloadsDir, id) {
                withContext(NonCancellable) {
                    // Operation waits for the cancellation command, not worker termination.
                    WorkManager.getInstance(app).cancelUniqueWork("download-$id").result.get()
                    val entry = app.store.state.value.downloads.find { it.id == id }
                    app.store.update { it.copy(downloads = it.downloads.filterNot { task -> task.id == id }) }
                    app.store.flush()
                    entry?.let { File(app.store.downloadsDir, it.fileName).delete() }
                    DownloadFiles.cleanup(app.store.downloadsDir, id)
                }
            }
        }
        suspend fun enqueue(app: NoveliaApplication, entry: DownloadEntry) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("id" to entry.id, "owner" to app.session.profile.value?.username)).setConstraints(Constraints.Builder().setRequiredNetworkType(if(app.store.state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build()
            withContext(Dispatchers.IO) {
                DownloadFiles.withTaskLock(app.store.downloadsDir, entry.id) {
                    withContext(NonCancellable) {
                        app.store.update { it.copy(downloads = it.downloads.filterNot { d -> d.id == entry.id } + entry.copy(status = "等待下载", error = null, workId = request.id.toString())) }
                        app.store.flush()
                        WorkManager.getInstance(app).enqueueUniqueWork("download-${entry.id}", ExistingWorkPolicy.REPLACE, request).result.get()
                    }
                }
            }
        }
        suspend fun pause(app: NoveliaApplication, id: String) = withContext(Dispatchers.IO) {
            DownloadFiles.withTaskLock(app.store.downloadsDir, id) {
                withContext(NonCancellable) {
                    WorkManager.getInstance(app).cancelUniqueWork("download-$id").result.get()
                    app.store.update { it.copy(downloads = it.downloads.map { d -> if(d.id == id) d.copy(status = "已暂停") else d }) }
                    app.store.flush()
                }
            }
        }
    }
}
