package cc.novelia.app.data.sync

import android.content.Context
import androidx.work.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.storage.hashName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val syncRun = BoundCloudSync()

/** Both manual and automatic retries use the application's resource ordering and session binding. */
suspend fun synchronizePending(app: NoveliaApplication, manual: Boolean = false, bookKey: String? = null,
    binding: SessionBinding = app.session.capture()): CloudReplayResult = syncRun.run(binding, app.session::ensureCurrent) {
    app.initialization.await()
    app.session.ensureCurrent(binding)
    check(app.store.recoveryIssue.value == null) { "请先恢复本地阅读资料" }
    val account = binding.account ?: throw ApiException(401, "请先登录")
    val before = app.store.state.value.syncStatus[account] ?: CloudSyncStatus()
    fun selectedPending() = pendingForSync(app.store.state.value.pending, binding, bookKey)
    val selectedIds = selectedPending().map { it.id }.toSet()
    val attemptedAt = System.currentTimeMillis()
    app.store.update { state ->
        val latest = state.syncStatus[account] ?: before
        state.copy(syncStatus = state.syncStatus + (account to latest.copy(lastAttemptAt = attemptedAt)))
    }
    try {
        val result = app.api.cloudMutations.replayEligible(account, ::selectedPending,
            { transform -> app.store.update { it.updateCloudPending(transform) } },
            if(manual) emptySet() else before.blockedActions
        ) { action ->
            app.session.ensureCurrent(binding)
            app.api.request(action.method, action.path, action.body, contentType = action.contentType, binding = binding)
        }
        app.session.ensureCurrent(binding)
        app.store.update { state ->
            val remaining = state.pending.filter { it.account == account }.map { it.id }.toSet()
            val latest = state.syncStatus[account] ?: before
            val failures = (latest.failures + result.failures).filterKeys { it in remaining }
            val blocked = ((if(manual) latest.blockedActions - selectedIds else latest.blockedActions) + result.blockedActions).intersect(remaining)
            val status = CloudSyncStatus(attemptedAt,
                if(result.completed > 0 || remaining.isEmpty()) System.currentTimeMillis() else before.lastSuccessAt,
                failures, blocked, result.requiresLogin)
            state.copy(syncStatus = state.syncStatus + (account to status))
        }
        result
    } finally {
        // Preserve removals even when WorkManager or an account switch cancels the running job.
        withContext(NonCancellable) { app.store.flush() }
    }
}

open class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NoveliaApplication
        app.initialization.await()
        if(!app.store.state.value.autoSync || app.store.recoveryIssue.value != null) return Result.success()
        val binding = app.session.capture()
        val account = binding.account ?: return Result.success()
        if(inputData.getString("account")?.let { it != account } == true) return Result.success()
        if(app.store.state.value.pending.none { it.account == account }) return Result.success()
        return try {
            val result = synchronizePending(app, binding = binding)
            app.session.ensureCurrent(binding)
            val state = app.store.state.value
            val blocked = state.syncStatus[account]?.blockedActions.orEmpty()
            val more = state.pending.any { it.account == account && it.id !in blocked }
            if(!result.requiresLogin && (result.retry || more)) Result.retry() else Result.success()
        } catch(_: SessionChangedException) { Result.success() }
        catch(error: CancellationException) { throw error }
        catch(_: Exception) { Result.retry() }
    }

    companion object {
        private const val TAG = "automatic-cloud-sync"
        private const val PERIODIC = "automatic-cloud-sync-fallback"
        fun configure(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if(!enabled) { manager.cancelAllWorkByTag(TAG); return }
            // A fallback also covers process death or a new intent arriving as one-time work ends.
            val periodic = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .addTag(TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
        fun enqueue(context: Context, account: String) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .addTag(TAG).setInputData(workDataOf("account" to account))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("cloud-sync-${hashName(account)}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
