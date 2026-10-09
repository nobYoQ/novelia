package cc.novelia.app.data.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class AppReleaseChannel { Stable, Preview }
enum class AppDownloadPhase { Idle, Checking, PreviewConsent, Downloading, Ready, Current, Failed }

@Serializable data class AppDownloadCandidate(
    val channel: AppReleaseChannel, val versionName: String, val apk: AppReleaseAsset,
    val versionCode: Long? = null, val run: Long = 0, val commit: String = "",
    val attempt: Long = 1,
) {
    val label: String get() = if(channel == AppReleaseChannel.Preview) "$versionName · 预览构建 $run.$attempt" else versionName
}

data class InstalledAppRelease(val versionName: String, val versionCode: Long, val run: Long = 0,
    val commit: String = "", val apkSha256: String? = null, val attempt: Long = 1)

internal fun AppDownloadCandidate.isNewerThan(installed: InstalledAppRelease): Boolean {
    val versionOrder = compareAppVersions(versionName, installed.versionName)
        ?: throw AppReleaseException("无法识别版本信息，请稍后重试")
    if(channel == AppReleaseChannel.Stable) return versionOrder > 0
    if(versionOrder < 0 || (versionCode ?: 0) < installed.versionCode) return false
    if(apk.sha256 != null && apk.sha256 == installed.apkSha256) return false
    if(versionOrder > 0 || (versionCode ?: 0) > installed.versionCode) return true
    if(installed.run > 0) return run > installed.run || (run == installed.run && attempt > installed.attempt)
    return commit.isBlank() || commit != installed.commit
}

internal fun parsePreviewCandidate(apk: AppReleaseAsset, text: String): AppDownloadCandidate {
    val fields = text.lineSequence().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    val version = fields["versionName"].orEmpty()
    val code = fields["versionCode"]?.toLongOrNull()
    val run = fields["run"]?.toLongOrNull()
    val attempt = fields["attempt"]?.toLongOrNull()
    val commit = fields["commit"].orEmpty()
    if(compareAppVersions(version, "0.0.0") == null || code == null || code <= 0 || run == null || run <= 0 ||
        attempt == null || attempt <= 0 || !commit.matches(Regex("[0-9a-f]{40}"))) throw AppReleaseException("预览构建信息不完整，暂时无法检查版本")
    return AppDownloadCandidate(AppReleaseChannel.Preview, version, apk, code, run, commit, attempt)
}

@Serializable data class AppDownloadRecord(val candidate: AppDownloadCandidate, val downloadId: Long,
    val fileName: String, val completionNotified: Boolean = false)

data class AppDownloadState(val phase: AppDownloadPhase = AppDownloadPhase.Idle,
    val candidate: AppDownloadCandidate? = null, val bytes: Long = 0, val total: Long = 0,
    val message: String? = null) {
    val progress: Float? get() = if(total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
}

enum class AppTransferStatus { Pending, Running, Paused, Complete, Failed, Missing }
data class AppTransfer(val status: AppTransferStatus, val bytes: Long = 0, val total: Long = 0, val message: String? = null)

interface AppDownloadBackend {
    suspend fun enqueue(candidate: AppDownloadCandidate): AppDownloadRecord
    suspend fun query(record: AppDownloadRecord): AppTransfer
    suspend fun verify(record: AppDownloadRecord)
    suspend fun remove(record: AppDownloadRecord)
}
interface AppDownloadRecordStore {
    suspend fun load(): Map<AppReleaseChannel, AppDownloadRecord>
    suspend fun save(records: Map<AppReleaseChannel, AppDownloadRecord>)
}

/** 两个渠道分别持久保存安装包身份；每次点击先取最新发行，再决定下载或安装。 */
class AppReleaseDownloadCoordinator(
    private val load: suspend (AppReleaseChannel) -> AppDownloadCandidate?,
    private val installed: suspend () -> InstalledAppRelease,
    private val backend: AppDownloadBackend,
    private val store: AppDownloadRecordStore,
) {
    private val lock = Mutex()
    private var restored = false
    private var records = emptyMap<AppReleaseChannel, AppDownloadRecord>()
    private val verified = mutableSetOf<Long>()
    private val mutableStates = MutableStateFlow(AppReleaseChannel.entries.associateWith { AppDownloadState() })
    val states = mutableStates.asStateFlow()
    private val mutableDialog = MutableStateFlow<AppReleaseChannel?>(null)
    val dialog = mutableDialog.asStateFlow()

    private fun state(channel: AppReleaseChannel, value: AppDownloadState) { mutableStates.value += channel to value }
    private suspend fun restore() { if(!restored) { records = store.load(); restored = true } }
    private suspend fun save(value: Map<AppReleaseChannel, AppDownloadRecord>) { store.save(value); records = value }
    fun dismiss() { mutableDialog.value = null }
    fun show(channel: AppReleaseChannel) { mutableDialog.value = channel }

    /** 已缓存且仍是最新的安装包直接交给调用方打开安装器。 */
    suspend fun check(channel: AppReleaseChannel): AppDownloadRecord? = lock.withLock {
        restore()
        val previousState = states.value.getValue(channel)
        mutableDialog.value = channel
        state(channel, AppDownloadState(AppDownloadPhase.Checking))
        try {
            val candidate = load(channel)
            if(candidate == null || !candidate.isNewerThan(installed())) {
                state(channel, AppDownloadState(AppDownloadPhase.Current, candidate, message = "当前已是最新${if(channel == AppReleaseChannel.Preview) "预览版" else "正式版"}，无需下载"))
                return@withLock null
            }
            val cached = records[channel]?.takeIf { it.candidate == candidate }
            if(cached != null) {
                val transfer = backend.query(cached)
                if(transfer.status == AppTransferStatus.Complete) {
                    val valid = try { backend.verify(cached); true }
                    catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { false }
                    if(valid) {
                        verified += cached.downloadId
                        save(records + (channel to cached.copy(completionNotified = true)))
                        state(channel, AppDownloadState(AppDownloadPhase.Ready, candidate, candidate.apk.size, candidate.apk.size))
                        return@withLock cached
                    }
                } else if(transfer.status in listOf(AppTransferStatus.Pending, AppTransferStatus.Running, AppTransferStatus.Paused)) {
                    state(channel, transferState(cached, transfer))
                    return@withLock null
                }
            }
            if(channel == AppReleaseChannel.Preview) state(channel, AppDownloadState(AppDownloadPhase.PreviewConsent, candidate))
            else start(candidate)
        } catch(cancelled: CancellationException) {
            state(channel, previousState)
            if(mutableDialog.value == channel) dismiss()
            throw cancelled
        }
        catch(error: Exception) { fail(channel, error) }
        null
    }

    suspend fun confirmPreview() = lock.withLock {
        val candidate = states.value[AppReleaseChannel.Preview]?.takeIf { it.phase == AppDownloadPhase.PreviewConsent }?.candidate
            ?: return@withLock
        try { start(candidate) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { fail(AppReleaseChannel.Preview, error) }
    }

    private suspend fun start(candidate: AppDownloadCandidate) = withContext(NonCancellable) {
        val channel = candidate.channel
        val previous = records[channel]
        val record = backend.enqueue(candidate)
        try { save(records + (channel to record)) }
        catch(error: Exception) { backend.remove(record); throw error }
        state(channel, AppDownloadState(AppDownloadPhase.Downloading, candidate, total = candidate.apk.size))
        if(previous != null) {
            try { backend.remove(previous) } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { }
            verified -= previous.downloadId
        }
    }

    /** 仅前台轮询；后台和进程退出时仍由系统下载，返回应用后恢复进度和完成提示。 */
    suspend fun refresh() = lock.withLock {
        restore()
        for((channel, record) in records.toMap()) {
            if(states.value[channel]?.phase in listOf(AppDownloadPhase.Checking, AppDownloadPhase.PreviewConsent, AppDownloadPhase.Current, AppDownloadPhase.Failed)) continue
            try {
                if(!record.candidate.isNewerThan(installed())) {
                    state(channel, AppDownloadState(AppDownloadPhase.Current, record.candidate, message = "当前已是最新版本，无需安装"))
                    continue
                }
                val transfer = backend.query(record)
                if(transfer.status == AppTransferStatus.Complete) {
                    if(record.downloadId !in verified) { backend.verify(record); verified += record.downloadId }
                    if(states.value[channel]?.phase == AppDownloadPhase.Failed) continue
                    state(channel, AppDownloadState(AppDownloadPhase.Ready, record.candidate, record.candidate.apk.size, record.candidate.apk.size))
                    if(!record.completionNotified && (mutableDialog.value == null || mutableDialog.value == channel)) {
                        save(records + (channel to record.copy(completionNotified = true)))
                        mutableDialog.value = channel
                    }
                } else state(channel, transferState(record, transfer))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { fail(channel, error) }
        }
    }

    suspend fun readyRecord(channel: AppReleaseChannel): AppDownloadRecord = lock.withLock {
        restore()
        val record = records[channel] ?: throw AppReleaseException("安装包不存在，请重新检查更新")
        if(backend.query(record).status != AppTransferStatus.Complete) throw AppReleaseException("安装包尚未下载完成")
        backend.verify(record)
        record
    }

    fun reportError(channel: AppReleaseChannel, error: Exception) { fail(channel, error); show(channel) }
    private fun fail(channel: AppReleaseChannel, error: Exception) {
        state(channel, AppDownloadState(AppDownloadPhase.Failed, records[channel]?.candidate,
            message = if(error is AppReleaseException) error.message else "操作未完成，请检查网络或存储空间后重试"))
    }
    private fun transferState(record: AppDownloadRecord, transfer: AppTransfer) = AppDownloadState(
        if(transfer.status in listOf(AppTransferStatus.Failed, AppTransferStatus.Missing)) AppDownloadPhase.Failed else AppDownloadPhase.Downloading,
        record.candidate, transfer.bytes, transfer.total.takeIf { it > 0 } ?: record.candidate.apk.size,
        transfer.message ?: when(transfer.status) {
            AppTransferStatus.Pending -> "正在等待下载"
            AppTransferStatus.Paused -> "下载已暂停，等待网络后自动继续"
            AppTransferStatus.Failed, AppTransferStatus.Missing -> "下载失败或文件已删除，请重试"
            else -> null
        })
}
