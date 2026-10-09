package cc.novelia.app.data.webdav

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.novelia.app.NoveliaApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal fun WebDavConfig.automaticallySyncable() = enabled && automatic && bound && selected.isNotEmpty()
internal fun retryWebDavFailure(error: Exception): Boolean = error is WebDavException &&
    error.failure in setOf(WebDavFailure.NETWORK, WebDavFailure.SERVER, WebDavFailure.RATE_LIMITED, WebDavFailure.CONFLICT)

class WebDavSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NoveliaApplication
        val configuration = app.webDavConfig.config.value
        if(!configuration.automaticallySyncable() || inputData.getLong("generation", -1) != configuration.generation) return Result.success()
        return try {
            app.webDav.synchronize(manual = false, expectedGeneration = configuration.generation)
            // KEEP 任务运行时可能有新编辑；退避后提交，避免立即再同步一轮。
            if(app.webDavConfig.config.value.generation == configuration.generation && app.webDav.hasPending()) Result.retry()
            else Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: WebDavConfigChangedException) { Result.success() }
        catch(error: Exception) {
            val retry = if(error is WebDavException) retryWebDavFailure(error) else
                app.webDav.status.value.domains.filterKeys { it in app.webDavConfig.config.value.selected }.values.any { it.retryable }
            if(retry) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "webdav-automatic-sync"
        private const val PERIODIC = "webdav-sync-periodic"
        fun configure(context: Context, configuration: WebDavConfig) {
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag(TAG)
            if(!configuration.automaticallySyncable()) return
            val request = PeriodicWorkRequestBuilder<WebDavSyncWorker>(configuration.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf("generation" to configuration.generation))
                .setConstraints(constraints(configuration)).addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueue(context: Context, configuration: WebDavConfig) {
            if(!configuration.automaticallySyncable()) return
            val request = OneTimeWorkRequestBuilder<WebDavSyncWorker>()
                .setInputData(workDataOf("generation" to configuration.generation))
                .setConstraints(constraints(configuration)).addTag(TAG)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            managerEnqueue(context, request)
        }

        private fun managerEnqueue(context: Context, request: androidx.work.OneTimeWorkRequest) {
            WorkManager.getInstance(context).enqueueUniqueWork("webdav-sync-pending", ExistingWorkPolicy.KEEP, request)
        }
        private fun constraints(configuration: WebDavConfig) = Constraints.Builder()
            .setRequiredNetworkType(if(configuration.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true).build()
    }
}
