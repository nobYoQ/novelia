package cc.novelia.app.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用级持有，不依赖设置页是否仍在显示。ProcessLifecycleOwner 的延迟 ON_STOP 排除配置重建。 */
internal class LauncherIconManager(context: Context) : DefaultLifecycleObserver {
    private val controller = LauncherIconController(
        AndroidLauncherIconBackend(context.applicationContext),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    )
    val state get() = controller.state

    init { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }

    fun select(id: String) = controller.select(id)
    override fun onStart(owner: LifecycleOwner) = controller.onForeground()
    override fun onStop(owner: LifecycleOwner) = controller.onBackground()
}

internal class AndroidLauncherIconBackend(private val context: Context) : LauncherIconBackend {
    private val packageManager = context.packageManager
    private val preferences by lazy { context.getSharedPreferences("launcher_icons", Context.MODE_PRIVATE) }
    private var icons: List<LauncherIcon> = emptyList()

    @Suppress("DEPRECATION")
    override fun load(): LauncherIconState {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
        icons = packageManager.getPackageInfo(context.packageName, flags).activities.orEmpty().mapNotNull { activity ->
            val data = activity.metaData ?: return@mapNotNull null
            val id = data.getString("novelia.launcher.id")?.removePrefix("icon:") ?: return@mapNotNull null
            if (activity.targetActivity != LauncherEntryActivity::class.java.name) return@mapNotNull null
            LauncherIcon(id, data.getString("novelia.launcher.title")?.removePrefix("title:") ?: id,
                activity.icon, activity.name, data.getBoolean("novelia.launcher.hidden"), data.getInt(LAUNCHER_SPLASH_THEME_META_DATA))
        }.sortedWith(compareBy<LauncherIcon> { it.id != DEFAULT_LAUNCHER_ICON }.thenBy { it.id })
        check(icons.any { it.id == DEFAULT_LAUNCHER_ICON })
        val saved = preferences.getString("selected_id", DEFAULT_LAUNCHER_ICON)
        val selected = icons.firstOrNull { it.id == saved }?.id ?: DEFAULT_LAUNCHER_ICON
        return LauncherIconState(icons = icons, selectedId = selected, enabledIds = enabledIds())
    }

    override fun saveSelection(id: String) {
        if (!preferences.edit().putString("selected_id", id).commit()) throw IOException("Unable to persist launcher icon selection")
    }

    override fun enabledIds(): Set<String> = icons.filter { icon ->
        when (packageManager.getComponentEnabledSetting(component(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.id == DEFAULT_LAUNCHER_ICON
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }.mapTo(mutableSetOf()) { it.id }

    override fun apply(id: String): Set<String> {
        val byId = icons.associateBy { it.id }
        val atomicChange: ((List<LauncherIconChange>) -> Unit)? = if (Build.VERSION.SDK_INT >= 33) {
            { changes -> packageManager.setComponentEnabledSettings(changes.map { item ->
                PackageManager.ComponentEnabledSetting(component(byId.getValue(item.id)), setting(item.enabled), PackageManager.DONT_KILL_APP)
            }) }
        } else null
        switchLauncherIconSafely(id, byId.keys, enabledIds(), change = { item ->
            packageManager.setComponentEnabledSetting(component(byId.getValue(item.id)), setting(item.enabled), PackageManager.DONT_KILL_APP)
        }, atomicChange = atomicChange)
        return enabledIds()
    }

    private fun component(icon: LauncherIcon) = ComponentName(context.packageName, icon.component)
    private fun setting(enabled: Boolean) = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
