package cc.novelia.app.files

import android.content.Context
import androidx.work.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.DownloadEntry
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NoveliaApplication
        val id = inputData.getString("id") ?: return Result.failure()
        val entry = app.store.state.value.downloads.find { it.id == id } ?: return Result.failure()
        val owner = inputData.getString("owner")
        if(owner != null && owner != app.session.profile.value?.username) { update(app, id, "需要登录", error = "请登录创建此下载的账号后重试"); return Result.failure() }
        val partial = File(app.store.downloadsDir, "$id.part"); val destination = File(app.store.downloadsDir, entry.fileName)
        try {
            update(app, id, "下载中", 0)
            val client = app.api.transport.newBuilder().followRedirects(true).readTimeout(120, TimeUnit.SECONDS).build()
            val parsed = entry.url.toHttpUrl(); require(parsed.scheme == "https" && parsed.host == "n.novelia.cc")
            val request = Request.Builder().url(parsed).apply { app.session.token?.let { header("Authorization", "Bearer $it") } }.build()
            client.newCall(request).execute().use { response ->
                if(!response.isSuccessful) error("下载失败（${response.code}），请检查译文是否完整或重新登录")
                val body = response.body ?: error("服务器没有返回文件")
                require(!body.contentType().toString().startsWith("text/html")) { "服务器返回了网页，请稍后重试" }
                val expected = body.contentLength(); require(expected <= 512L * 1024 * 1024) { "文件超过 512 MB" }
                var total = 0L; var previousProgress = -1
                partial.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    while(true) {
                        if(isStopped) return Result.failure()
                        val count = input.read(buffer); if(count < 0) break
                        total += count; require(total <= 512L * 1024 * 1024) { "文件超过 512 MB" }; output.write(buffer, 0, count)
                        val progress = if(expected > 0) (total * 100 / expected).toInt() else 0
                        if(progress != previousProgress) { previousProgress = progress; update(app, id, "下载中", progress) }
                    }
                } }
                if(expected >= 0 && total != expected) error("下载不完整，请重试")
                if(total == 0L) error("下载内容为空")
            }
            if(!partial.renameTo(destination)) { partial.copyTo(destination, overwrite = true); partial.delete() }
            update(app, id, "已完成", 100)
            cc.novelia.app.data.AppNotifications.show(app, id.hashCode(), "下载已完成", entry.title)
            return Result.success()
        } catch(e: Exception) {
            if(isStopped || e is kotlinx.coroutines.CancellationException) return Result.failure()
            update(app, id, "失败", error = if(e is java.io.IOException) "网络中断，点击重试重新下载" else e.message?.take(160) ?: "下载失败")
            return Result.failure()
        }
    }
    private fun update(app: NoveliaApplication, id: String, status: String, progress: Int? = null, error: String? = null) { app.store.update { it.copy(downloads = it.downloads.map { d -> if(d.id == id) d.copy(status = status, progress = progress ?: d.progress, error = error) else d }) } }
    companion object {
        fun enqueue(app: NoveliaApplication, entry: DownloadEntry) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("id" to entry.id, "owner" to app.session.profile.value?.username)).setConstraints(Constraints.Builder().setRequiredNetworkType(if(app.store.state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build()
            app.store.update { it.copy(downloads = it.downloads.filterNot { d -> d.id == entry.id } + entry.copy(status = "等待下载", error = null)) }
            WorkManager.getInstance(app).enqueueUniqueWork("download-${entry.id}", ExistingWorkPolicy.REPLACE, request)
        }
        fun pause(app: NoveliaApplication, id: String) { WorkManager.getInstance(app).cancelUniqueWork("download-$id"); app.store.update { it.copy(downloads = it.downloads.map { d -> if(d.id == id) d.copy(status = "已暂停") else d }) } }
    }
}
