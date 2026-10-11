package cc.novelia.app.data.appupdate

import android.content.Context
import cc.novelia.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 每次前台启动后检查；自动检查遵守本机开关及提醒偏好，手动检查始终可用。 */
class AppUpdateChecker(context: Context, private val load: suspend () -> AppRelease? = AppReleaseClient()::latest,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val automaticChecksEnabled: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("app-release-updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow<AppRelease?>(null)
    val available = mutable.asStateFlow()

    suspend fun check(force: Boolean = false): AppRelease? = lock.withLock {
        if(!force && !automaticChecksEnabled()) {
            mutable.value = null
            return@withLock null
        }
        val release = try { load() }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { if(force) throw error else return@withLock null }
        val newer = release?.takeIf { it.availableFor(currentVersion) }
        mutable.value = newer?.takeIf {
            force || (automaticChecksEnabled() && it.tag != preferences.getString("ignoredVersion", null) && now() >= preferences.getLong("promptAfter", 0L))
        }
        newer
    }

    fun later() {
        preferences.edit().putLong("promptAfter", now() + 24 * 60 * 60_000L).apply()
        mutable.value = null
    }

    fun ignore(release: AppRelease) {
        preferences.edit().putString("ignoredVersion", release.tag).apply()
        mutable.value = null
    }
}
