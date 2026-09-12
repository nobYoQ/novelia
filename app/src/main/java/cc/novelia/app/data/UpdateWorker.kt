package cc.novelia.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.R
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NoveliaApplication
        val books = app.store.state.value.books.filter { !it.book.ref.isLocal }.take(60)
        var count = 0; var failed = 0
        for(saved in books) {
            if(isStopped) return Result.failure()
            try {
                val ref = saved.book.ref
                val updated = if(ref.isWenku) app.api.get<WenkuDetail>("wenku/${ref.id}").let { it.card(ref).copy(total = it.volumeJp.size + it.volumeZh.size) } else app.api.get<WebDetail>("novel/${ref.key}").card(ref)
                val changed = updated.total > saved.book.total || updated.translated > saved.book.translated
                if(changed) count++
                app.store.update { state -> state.copy(books = state.books.map { b -> if(b.book.ref == ref) b.copy(book = updated, hasUpdates = b.hasUpdates || changed) else b }) }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { failed++ }
            delay(1500)
        }
        app.store.update { it.copy(drafts = it.drafts + ("updates:last" to "${System.currentTimeMillis()}|$count|$failed")) }
        app.store.flush()
        if(count > 0) AppNotifications.show(app, 201, "书架里有新的故事", "$count 本小说有新章节或译文，打开书架查看。")
        return if(books.isNotEmpty() && failed == books.size) Result.retry() else Result.success()
    }
    companion object {
        fun schedule(app: NoveliaApplication, enabled: Boolean) {
            val work = WorkManager.getInstance(app)
            if(!enabled) { work.cancelUniqueWork("bookshelf-updates"); return }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork("bookshelf-updates", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun checkNow(app: NoveliaApplication) { WorkManager.getInstance(app).enqueueUniqueWork("bookshelf-update-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    }
}
object AppNotifications {
    fun show(context: Context, id: Int, title: String, text: String) {
        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("updates", "下载与书架更新", NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, "updates").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}
