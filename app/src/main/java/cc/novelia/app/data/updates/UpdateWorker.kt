package cc.novelia.app.data.updates

import android.content.Context
import androidx.work.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.model.withKnownUpdateTime
import cc.novelia.app.data.storage.appJson
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
                val raw = app.api.request("GET", if(ref.isWenku) "wenku/${ref.id}" else "novel/${ref.key}", binding = binding)
                val updated = if(ref.isWenku) appJson.decodeFromString<WenkuDetail>(raw).also(app.api::observeKeywords).card(ref)
                    else appJson.decodeFromString<WebDetail>(raw).also(app.api::observeKeywords).card(ref, binding.account)
                app.session.ensureCurrent(binding)
                val current = updated.updateSnapshot(System.currentTimeMillis())
                app.store.update { state ->
                    val existing = state.books.firstOrNull { it.book.ref == ref } ?: return@update state
                    val previous = state.updateSnapshots[ref.key] ?: existing.book.updateSnapshot()
                    val delta = detectBookUpdate(previous, current, ref.isWenku)
                    val prior = state.bookUpdates[ref.key].takeIf { existing.hasUpdates }
                    val changes = if(delta.hasChanges) prior?.accumulate(delta) ?: delta else prior
                    if(delta.relevantTo(state.bookSettings[ref.key] ?: state.reader)) count++
                    state.copy(
                        books = state.books.map { b -> if(b.book.ref == ref) b.copy(book = updated.withKnownUpdateTime(b.book), hasUpdates = b.hasUpdates || delta.hasChanges) else b },
                        updateSnapshots = state.updateSnapshots + (ref.key to current),
                        bookUpdates = if(changes != null) state.bookUpdates + (ref.key to changes) else state.bookUpdates - ref.key
                    )
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: SessionChangedException) { return Result.success() }
            catch(e: Exception) { failed++ }
            // Persist progress before yielding. If the OS stops a long run, the next one begins
            // with the books that would otherwise remain permanently at the end of the shelf.
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
