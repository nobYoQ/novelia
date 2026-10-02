package cc.novelia.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.ui.theme.activityOrNull

/** 由根导航唯一控制，避免跨章过渡时旧阅读器销毁把新阅读器的状态栏重新显示。 */
@Composable internal fun ReadingStatusBar(hidden: Boolean) {
    val activity = LocalContext.current.activityOrNull()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner, hidden) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        fun apply() {
            controller?.apply {
                if(hidden) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                } else show(WindowInsetsCompat.Type.statusBars())
            }
        }
        apply()
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) apply() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller?.show(WindowInsetsCompat.Type.statusBars())
            if(previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
}
