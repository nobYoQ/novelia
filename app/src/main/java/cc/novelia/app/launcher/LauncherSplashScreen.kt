package cc.novelia.app.launcher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.startup.StartupAppearance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

internal const val LAUNCHER_SPLASH_THEME_META_DATA = "novelia.launcher.splashTheme"
internal val LAUNCHER_SPLASH_VARIANTS = listOf("Light", "Dark", "Amoled", "SystemAmoled", "Monochrome")

internal fun LauncherIcon.splashThemeFor(appearance: StartupAppearance): Int =
    splashThemes[appearance.splashVariant]?.takeIf { it != 0 } ?: splashTheme

/** 系统在应用进程启动前绘制 SplashScreen，因此要提前持久化当前已生效图标的主题。 */
internal fun ComponentActivity.observeLauncherSplashScreen(manager: LauncherIconManager, appearance: StateFlow<StartupAppearance>) {
    if (Build.VERSION.SDK_INT < 31) return
    // 退到后台后仍需收集，才能在入口切换完成时更新；不能限定为 STARTED/RESUMED。
    lifecycleScope.launch {
        combine(manager.state, appearance) { icons, colors -> icons.current?.splashThemeFor(colors) }
            .filterNotNull().distinctUntilChanged().collect { theme ->
            splashScreen.setSplashScreenTheme(theme)
        }
    }
}

/** 补齐升级后的首次启动，以及进程在图标切换后被结束的情况；在路由到主界面之前执行。 */
@Suppress("DEPRECATION")
internal fun Activity.synchronizeLauncherEntrySplashScreen() {
    if (Build.VERSION.SDK_INT < 31) return
    val entry = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
    val variant = (application as NoveliaApplication).startupAppearance.state.value.splashVariant
    val key = LAUNCHER_SPLASH_THEME_META_DATA + if(variant.isEmpty()) "" else ".$variant"
    splashScreen.setSplashScreenTheme(entry.metaData?.getInt(key) ?: 0)
}
