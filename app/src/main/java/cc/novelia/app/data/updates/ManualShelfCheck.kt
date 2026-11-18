package cc.novelia.app.data.updates

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class ShelfCheckStatus(
    val message: String,
    val active: Boolean = false,
    val progress: Float? = null,
    val finishedAt: Long = 0
)

internal object ManualShelfCheck {
    const val NAME = "bookshelf-update-now"

    fun progress(checked: Int, total: Int) = workDataOf("checked" to checked, "total" to total)
    fun error(message: String) = workDataOf("reason" to message, "finishedAt" to System.currentTimeMillis())

    private fun List<WorkInfo>.current() = firstOrNull { !it.state.isFinished } ?: firstOrNull()

    fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME)
        .map { it.current()?.let(::status) }.distinctUntilChanged()

    suspend fun checkNow(context: Context): String {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request).await()
        val requests = manager.getWorkInfosForUniqueWorkFlow(NAME).first()
        val accepted = requests.firstOrNull { it.id == request.id } ?: requests.current()
            ?: return "未能找到本次书架检查，请重试"
        val completed = if(accepted.state.isFinished) accepted else
            manager.getWorkInfoByIdFlow(accepted.id).first { it == null || it.state.isFinished }
        return completed?.let(::status)?.message ?: "本次检查已结束，请查看最近检查结果"
    }

    internal fun status(work: WorkInfo): ShelfCheckStatus? = when(work.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ShelfCheckStatus("检查已排队，等待网络和系统调度", active = true)
        WorkInfo.State.RUNNING -> {
            val total = work.progress.getInt("total", 0)
            val checked = work.progress.getInt("checked", 0).coerceIn(0, total.coerceAtLeast(0))
            if(total > 0) ShelfCheckStatus("正在检查书架：$checked / $total 本", active = true, progress = checked.toFloat() / total)
            else ShelfCheckStatus("正在准备检查书架…", active = true)
        }
        else -> {
            val report = ShelfCheckReport.decode(work.outputData.getString(ShelfCheckReport.KEY))
            when {
                report != null -> ShelfCheckStatus(report.message, finishedAt = report.finishedAt)
                // 旧版成功任务只在书库中保存结果。
                work.state == WorkInfo.State.SUCCEEDED -> null
                else -> {
                    val message = work.outputData.getString("reason") ?: if(work.state == WorkInfo.State.CANCELLED)
                        "手动检查已取消，可重新检查" else "手动检查失败，请重试"
                    ShelfCheckStatus(message, finishedAt = work.outputData.getLong("finishedAt", 0))
                }
            }
        }
    }
}
