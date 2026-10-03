package cc.novelia.app.launcher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val LAUNCHER_SPLASH_THEME_META_DATA = "novelia.launcher.splashTheme"

/** 系统在应用进程启动前绘制 SplashScreen，因此要提前持久化当前已生效图标的主题。 */
internal fun ComponentActivity.observeLauncherSplashScreen(manager: LauncherIconManager) {
    if (Build.VERSION.SDK_INT < 31) return
    // 退到后台后仍需收集，才能在入口切换完成时更新；不能限定为 STARTED/RESUMED。
    lifecycleScope.launch {
        manager.state.map { it.current?.splashTheme }.filterNotNull().distinctUntilChanged().collect { theme ->
            splashScreen.setSplashScreenTheme(theme)
        }
    }
}

/** 补齐升级后的首次启动，以及进程在图标切换后被结束的情况；在路由到主界面之前执行。 */
@Suppress("DEPRECATION")
internal fun Activity.synchronizeLauncherEntrySplashScreen() {
    if (Build.VERSION.SDK_INT < 31) return
    val entry = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
    splashScreen.setSplashScreenTheme(entry.metaData?.getInt(LAUNCHER_SPLASH_THEME_META_DATA) ?: 0)
}
