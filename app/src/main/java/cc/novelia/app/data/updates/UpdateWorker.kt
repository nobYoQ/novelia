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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 顺序检查书架远端元数据，比较章节、译文和文库分卷快照，并累计尚未查看的更新摘要。
 * 手动与定时检查共用互斥锁；每本书处理后保存游标，使系统中止长任务后能轮到书架后部条目。
 * 单本失败继续检查其他书，全部失败才请求调度器重试；会话切换时结束原账号这轮检查。
 */
open class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = checkLock.withLock { checkBooks() }
    private suspend fun checkBooks(): Result {
        val app = applicationContext as NoveliaApplication
        app.initialization.await()
        if(app.store.recoveryIssue.value != null) return Result.failure()
        val binding = app.session.capture()
        val library = app.store.state.value
        val books = booksForUpdate(library.books, library.drafts["updates:cursor"])
        var count = 0; var failed = 0
        for(saved in books) {
            if(isStopped) return Result.failure()
            try {
                val ref = saved.book.ref
                val observedAt = System.currentTimeMillis()
                val generation = app.store.cacheGeneration.value
                val raw = app.api.request("GET", if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}", binding = binding)
                val updated = if(ref.isWenku) appJson.decodeFromString<WenkuDetail>(raw).also(app.api::observeKeywords).card(ref)
                    else appJson.decodeFromString<WebDetail>(raw).also(app.api::observeKeywords).card(ref, binding.account)
                app.session.ensureCurrent(binding)
                val current = updated.updateSnapshot(observedAt)
                // 检查取得的目录同时供阅读器使用，避免新章计数已更新而目录仍停在旧末章。
                app.store.withCacheGeneration(generation) {
                    app.metadataCache.write(hashName("${binding.account ?: "guest"}:${if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}"}"), raw, observedAt)
                }
                app.store.update { state ->
                    val existing = state.books.firstOrNull { it.book.ref == ref } ?: return@update state
                    val previous = state.updateSnapshots[ref.key] ?: existing.book.updateSnapshot()
                    val delta = detectBookUpdate(previous, current, ref.isWenku)
                    val next = state.withBookUpdate(updated, observedAt)
                    if(next === state) return@update state
                    val unread = next.bookUpdates[ref.key]
                    val unreadDelta = delta.copy(newChapters = if((unread?.newChapters ?: 0) > 0) delta.newChapters else 0,
                        translations = delta.translations.filterKeys { (unread?.translations?.get(it) ?: 0) > 0 })
                    if(unreadDelta.relevantTo(state.bookSettings[ref.key] ?: state.reader)) count++
                    next
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: SessionChangedException) { return Result.success() }
            catch(e: Exception) { failed++ }
            // 让出执行前保存检查进度；若系统中断长任务，下一轮优先处理
            // 原本会一直留在书架尾部、得不到检查的书目。
            app.store.update { it.copy(drafts = it.drafts + ("updates:cursor" to saved.book.ref.key)) }
            app.store.flush()
            delay(1500)
        }
        app.store.update { it.copy(drafts = (it.drafts - "updates:cursor") + ("updates:last" to "${System.currentTimeMillis()}|$count|$failed")) }
        app.store.flush()
        if(count > 0) AppNotifications.show(app, 201, "书架里有新的故事", "$count 本小说有新章节或译文，打开书架查看。")
        return if(books.isNotEmpty() && failed == books.size) Result.retry() else Result.success()
    }
    companion object {
        private val checkLock = Mutex()
        fun schedule(app: NoveliaApplication, enabled: Boolean) {
            val work = WorkManager.getInstance(app)
            if(!enabled) { work.cancelUniqueWork("bookshelf-updates"); return }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork("bookshelf-updates", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun checkNow(app: NoveliaApplication) { WorkManager.getInstance(app).enqueueUniqueWork("bookshelf-update-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    }
}
