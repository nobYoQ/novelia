package cc.novelia.app.ui.navigation

import android.app.DownloadManager
import android.content.Intent
import cc.novelia.app.data.updates.AppRelease
import cc.novelia.app.ui.components.friendlyMessage
import kotlinx.coroutines.CancellationException

internal fun AppController.downloadAppRelease(release: AppRelease? = null, preview: Boolean = false,
    onQueued: () -> Unit = {}, onError: ((String) -> Unit)? = null) {
    if(app.appReleaseDownloads.preparing.value) return
    action {
        val started = try { app.appReleaseDownloads.download(release, preview) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            if(onError == null) throw error
            onError(error.friendlyMessage())
            return@action
        }
        onQueued()
        message(if(started) "已开始下载安装包，完成后点按系统通知安装" else "该安装包正在下载，请查看系统下载进度",
            actionLabel = "查看下载", onAction = { action {
                app.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } })
    }
}
