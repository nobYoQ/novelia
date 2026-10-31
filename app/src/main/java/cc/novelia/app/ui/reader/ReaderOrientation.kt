package cc.novelia.app.ui.reader

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import cc.novelia.app.ui.theme.activityOrNull

/** 根导航唯一控制方向；旧章节的退出动画及旋转销毁都不能重置新页面。 */
@Composable internal fun ReaderOrientation(reading: Boolean, orientation: String) {
    val activity = LocalContext.current.activityOrNull()
    val requested = if(!reading) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else when(orientation) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    SideEffect { if(activity != null && activity.requestedOrientation != requested) activity.requestedOrientation = requested }
}
