package cc.novelia.app.data

import android.content.Context
import androidx.annotation.Keep
import androidx.work.WorkerParameters

/** Retains the class name stored in WorkManager for jobs scheduled before the package move. */
@Keep
class UpdateWorker(context: Context, params: WorkerParameters) :
    cc.novelia.app.data.updates.UpdateWorker(context, params)
