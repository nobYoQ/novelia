package cc.novelia.app.data.updates

import android.content.Context
import cc.novelia.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 前台启动后检查，成功检查间隔一天；离线失败间隔一小时，不阻塞首屏。 */
class AppUpdateChecker(context: Context, private val load: suspend () -> AppRelease? = AppReleaseClient()::latest,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("app-release-updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow<AppRelease?>(null)
    val available = mutable.asStateFlow()

    suspend fun check(force: Boolean = false): AppRelease? = lock.withLock {
        val time = now()
        if(!force && time < preferences.getLong("nextCheckAt", 0L)) return@withLock mutable.value
        preferences.edit().putLong("nextCheckAt", time + 60 * 60_000L).apply()
        val release = try { load() }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { if(force) throw error else return@withLock null }
        preferences.edit().putLong("nextCheckAt", time + 24 * 60 * 60_000L).apply()
        val newer = release?.takeIf { it.availableFor(currentVersion) }
        mutable.value = newer?.takeIf {
            force || (it.tag != preferences.getString("ignoredVersion", null) && time >= preferences.getLong("promptAfter", 0L))
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
