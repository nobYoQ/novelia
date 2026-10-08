package cc.novelia.app.data.updates

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** APK 交给系统下载服务，退出页面或应用进程结束后仍可继续下载。 */
class AppReleaseDownloader(context: Context, private val client: AppReleaseClient = AppReleaseClient()) {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val mutablePreparing = MutableStateFlow(false)
    val preparing = mutablePreparing.asStateFlow()

    /** 返回 false 表示同一安装包已在系统下载队列中。 */
    suspend fun download(release: AppRelease? = null, preview: Boolean = false): Boolean = lock.withLock {
        mutablePreparing.value = true
        try {
            val selected = (release ?: if(preview) client.preview() else client.latest())
                ?: throw AppReleaseException(if(preview) "暂时没有可下载的预览包" else "暂时没有可下载的正式版")
            if(!preview && selected.prerelease) throw AppReleaseException("此发行版是预览版，请从预览包入口下载")
            val apk = selected.apkFor(Build.SUPPORTED_ABIS.toList())
                ?: throw AppReleaseException("此发行版暂未提供适合当前设备的 APK")
            withContext(Dispatchers.IO) { enqueue(apk, selected.tag) }
        } finally {
            mutablePreparing.value = false
        }
    }

    private fun enqueue(apk: AppReleaseAsset, tag: String): Boolean {
        val manager = app.getSystemService(DownloadManager::class.java) ?: throw AppReleaseException("系统下载服务不可用")
        val active = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED)
        manager.query(active)?.use { cursor ->
            val uriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            while(cursor.moveToNext()) {
                if(cursor.getString(uriColumn) == apk.downloadUrl) return false
            }
        }
        // 每次重新下载使用新文件名，滚动预览替换附件后不会误用旧 APK 或撞上已有文件。
        val fileName = "${apk.name.dropLast(4).take(180)}-${UUID.randomUUID()}.apk"
        val request = DownloadManager.Request(Uri.parse(apk.downloadUrl))
            .setTitle(apk.name)
            .setDescription("Novelia $tag · 下载完成后点按通知打开安装包")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            // Android 8/9 使用应用外部目录，不额外索取存储权限。
            request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        try { manager.enqueue(request) }
        catch(error: IllegalArgumentException) { throw AppReleaseException("无法创建下载任务，请检查系统下载管理器是否启用") }
        catch(error: SecurityException) { throw AppReleaseException("无法创建下载任务，请检查系统下载管理器权限") }
        return true
    }
}
