package cc.novelia.app.data.updates

import android.content.Context
import androidx.work.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 顺序检查书架远端元数据，比较章节、译文和文库分卷快照，并累计尚未查看的更新摘要。
 * 手动与定时检查共用互斥锁；每本书处理后保存游标，使系统中止长任务后能轮到书架后部条目。
 * 单本失败继续检查其他书；定时检查全部失败时请求重试，手动检查直接报告本轮结果。
 */
open class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = checkLock.withLock { checkBooks() }
    private suspend fun checkBooks(): Result {
        val app = applicationContext as NoveliaApplication
        app.initialization.await()
        if(app.store.recoveryIssue.value != null) return Result.failure(ManualShelfCheck.error("本地书库处于保护状态，请先恢复书库再检查"))
        val binding = app.session.capture()
        val library = app.store.state.value
        val books = booksForUpdate(library.books, library.drafts["updates:cursor"])
        // 旧版已经排队的一次性任务也应反馈进度和终态。
        val manual = WorkManager.getInstance(app).getWorkInfoByIdFlow(id).first()?.periodicityInfo == null
        var updatedCount = 0
        var notificationCount = 0
        var failed = 0
        if(manual) setProgress(ManualShelfCheck.progress(0, books.size))
        for((index, saved) in books.withIndex()) {
            if(isStopped) return Result.failure()
            try {
                val ref = saved.book.ref
                val observedAt = System.currentTimeMillis()
                val generation = app.store.cacheGeneration.value
                val raw = app.api.request("GET", if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}", binding = binding)
                val updated = if(ref.isWenku) appJson.decodeFromString<WenkuDetail>(raw).also(app.api::observeKeywords).card(ref)
                    else appJson.decodeFromString<WebDetail>(raw).also(app.api::observeKeywords).card(ref, binding.account)
                app.session.ensureCurrent(binding)
                // 检查取得的目录同时供阅读器使用，避免新章计数已更新而目录仍停在旧末章。
                app.store.withCacheGeneration(generation) {
                    app.metadataCache.write(hashName("${binding.account ?: "guest"}:${if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}"}"), raw, observedAt)
                }
                var applied: AppliedBookUpdate? = null
                app.store.update { state ->
                    state.applyBookUpdate(updated, observedAt).also { applied = it }.state
                }
                if(applied?.delta != null) updatedCount++
                if(applied?.shouldNotify(ref) == true) notificationCount++
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: SessionChangedException) {
                return if(manual) Result.failure(ManualShelfCheck.error("账号或书源已切换，本次检查已中止，请重新检查")) else Result.success()
            }
            catch(e: Exception) { failed++ }
            // 让出执行前保存检查进度；若系统中断长任务，下一轮优先处理
            // 原本会一直留在书架尾部、得不到检查的书目。
            app.store.update { it.copy(drafts = it.drafts + ("updates:cursor" to saved.book.ref.key)) }
            app.store.flush()
            if(manual) setProgress(ManualShelfCheck.progress(index + 1, books.size))
            if(index < books.lastIndex) delay(1500)
        }
        val report = ShelfCheckReport(System.currentTimeMillis(), updatedCount, failed, books.size).encode()
        app.store.update { it.copy(drafts = (it.drafts - "updates:cursor") + (ShelfCheckReport.KEY to report)) }
        app.store.flush()
        if(notificationCount > 0) AppNotifications.show(app, 201, "书架里有新的故事", "$notificationCount 本小说有更新，打开书架查看。")
        val output = workDataOf(ShelfCheckReport.KEY to report)
        return if(books.isNotEmpty() && failed == books.size) {
            if(manual) Result.failure(output) else Result.retry()
        } else Result.success(output)
    }
    companion object {
        private val checkLock = Mutex()
        fun schedule(app: NoveliaApplication, enabled: Boolean) {
            val work = WorkManager.getInstance(app)
            if(!enabled) { work.cancelUniqueWork("bookshelf-updates"); return }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork("bookshelf-updates", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
