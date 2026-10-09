package cc.novelia.app.data.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.storage.appJson
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class AppReleaseDownloader(context: Context, client: AppReleaseClient = AppReleaseClient()) {
    private val app = context.applicationContext
    private val backend = AndroidAppDownloadBackend(app)
    private var installed: InstalledAppRelease? = null
    val coordinator = AppReleaseDownloadCoordinator(
        load = { channel -> client.candidate(channel, Build.SUPPORTED_ABIS.toList(), BuildConfig.VERSION_NAME) },
        installed = { withContext(Dispatchers.IO) {
            installed ?: InstalledAppRelease(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(),
                BuildConfig.PREVIEW_RUN_NUMBER, BuildConfig.SOURCE_COMMIT, sha256(File(app.applicationInfo.sourceDir)), BuildConfig.PREVIEW_RUN_ATTEMPT)
                .also { installed = it }
        } }, backend = backend, store = PreferenceDownloadRecordStore(app))
    private val mutableInstallRequest = MutableStateFlow<AppReleaseChannel?>(null)
    val installRequest = mutableInstallRequest.asStateFlow()
    private var installationPending = false
    fun requestInstall(channel: AppReleaseChannel) {
        if(installationPending) return
        installationPending = true
        mutableInstallRequest.value = channel
    }
    fun consumeInstallRequest() { mutableInstallRequest.value = null }
    fun finishInstallRequest() { installationPending = false }

    suspend fun installationIntent(channel: AppReleaseChannel): Intent {
        val record = coordinator.readyRecord(channel)
        return backend.installationIntent(record)
    }
}

private class PreferenceDownloadRecordStore(context: Context) : AppDownloadRecordStore {
    private val preferences = context.getSharedPreferences("app-apk-downloads", Context.MODE_PRIVATE)
    override suspend fun load(): Map<AppReleaseChannel, AppDownloadRecord> = withContext(Dispatchers.IO) {
        val raw = preferences.getString("records", null) ?: return@withContext emptyMap()
        try { appJson.decodeFromString<Map<AppReleaseChannel, AppDownloadRecord>>(raw) }
        catch(_: Exception) { emptyMap() }
    }
    override suspend fun save(records: Map<AppReleaseChannel, AppDownloadRecord>) = withContext(Dispatchers.IO) {
        if(!preferences.edit().putString("records", appJson.encodeToString(records)).commit())
            throw AppReleaseException("无法保存下载记录，请检查存储空间后重试")
    }
}

internal class AndroidAppDownloadBackend(private val app: Context) : AppDownloadBackend {
    private val manager get() = app.getSystemService(DownloadManager::class.java)
        ?: throw AppReleaseException("系统下载服务不可用")

    fun file(record: AppDownloadRecord): File {
        if(!record.fileName.matches(Regex("[a-f0-9-]+\\.apk"))) throw AppReleaseException("安装包记录无效，请重新下载")
        val root = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw AppReleaseException("下载目录不可用，请检查存储空间")
        return File(root, "app-updates/${record.fileName}")
    }

    fun installationIntent(record: AppDownloadRecord): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file(record))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override suspend fun enqueue(candidate: AppDownloadCandidate): AppDownloadRecord = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.apk"
        val directory = file(AppDownloadRecord(candidate, 0, fileName)).parentFile!!
        if(!directory.isDirectory && !directory.mkdirs()) throw AppReleaseException("无法创建下载目录，请检查存储空间")
        val request = DownloadManager.Request(Uri.parse(candidate.apk.downloadUrl))
            .setTitle("Novelia ${candidate.label}")
            .setDescription("下载完成后返回 Novelia 安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "app-updates/$fileName")
        try { AppDownloadRecord(candidate, manager.enqueue(request), fileName) }
        catch(_: Exception) { throw AppReleaseException("无法创建下载任务，请检查系统下载服务和存储空间") }
    }

    override suspend fun query(record: AppDownloadRecord): AppTransfer = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(record.downloadId))?.use { cursor ->
            if(!cursor.moveToFirst()) return@withContext AppTransfer(AppTransferStatus.Missing)
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val status = when(number(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_PENDING -> AppTransferStatus.Pending
                DownloadManager.STATUS_RUNNING -> AppTransferStatus.Running
                DownloadManager.STATUS_PAUSED -> AppTransferStatus.Paused
                DownloadManager.STATUS_SUCCESSFUL -> if(file(record).isFile) AppTransferStatus.Complete else AppTransferStatus.Missing
                else -> AppTransferStatus.Failed
            }
            val message = if(status == AppTransferStatus.Failed) when(number(DownloadManager.COLUMN_REASON).toInt()) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请释放空间后重试"
                else -> "下载失败，请检查网络后重试"
            } else null
            AppTransfer(status, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), message)
        } ?: AppTransfer(AppTransferStatus.Missing)
    }

    @Suppress("DEPRECATION")
    override suspend fun verify(record: AppDownloadRecord) = withContext(Dispatchers.IO) {
        val file = file(record)
        val candidate = record.candidate
        if(!file.isFile || file.length() != candidate.apk.size) throw AppReleaseException("安装包不完整，请重新下载")
        if(candidate.apk.sha256?.let { it != sha256(file) } == true)
            throw AppReleaseException("安装包校验失败，预览附件可能已更新，请重新检查下载")
        val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw AppReleaseException("安装包无法读取，请重新下载")
        val versionCode = if(Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if(info.packageName != app.packageName || info.versionName != candidate.versionName ||
            (candidate.versionCode != null && versionCode != candidate.versionCode))
            throw AppReleaseException("安装包与发行信息不一致，请重新检查更新")
        if(versionCode < BuildConfig.VERSION_CODE) throw AppReleaseException("安装包版本低于当前应用，已停止安装")
    }

    override suspend fun remove(record: AppDownloadRecord) { withContext(Dispatchers.IO) { manager.remove(record.downloadId) } }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
