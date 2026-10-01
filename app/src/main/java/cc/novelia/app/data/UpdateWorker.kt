package cc.novelia.app.data

import android.content.Context
import androidx.annotation.Keep
import androidx.work.WorkerParameters

/** 保留 WorkManager 在包迁移前保存的类名，使既有后台任务仍可创建。 */
@Keep
class UpdateWorker(context: Context, params: WorkerParameters) :
    cc.novelia.app.data.updates.UpdateWorker(context, params)
