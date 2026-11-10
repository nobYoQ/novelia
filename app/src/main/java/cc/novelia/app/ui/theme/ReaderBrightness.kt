package cc.novelia.app.ui.theme

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.data.model.ReaderBrightness
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

internal val LocalReaderBrightness = compositionLocalOf<Float?> { null }
internal val LocalReaderBrightnessPreview = staticCompositionLocalOf<(ReaderBrightness?) -> Unit> { {} }

@Composable internal fun hasReaderLightSensor(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }
}

/** 仅前台监听光线；平滑照度、忽略小幅波动，再渐变窗口亮度，不触发正文重排或设置写入。 */
@Composable internal fun rememberReaderBrightness(preferences: ReaderBrightness): Float {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    var lux by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(manager, sensor, lifecycle, preferences.mode) {
        var average: Float? = null
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return
                val next = average?.let { it + .25f * (value - it) } ?: value
                average = next
                if (lux == null || abs(next - lux!!) >= max(.5f, lux!! * .15f)) lux = next
            }
        }
        fun stop() { manager?.unregisterListener(listener); average = null; lux = null }
        fun start() {
            if (preferences.mode == "adaptive" && sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    val target = preferences.target(lux)
    val brightness = remember { Animatable(target) }
    LaunchedEffect(target, preferences.mode) {
        if (target < 0 || brightness.value < 0) brightness.snapTo(target)
        else {
            if (preferences.mode == "adaptive" && lux != null) delay(500)
            brightness.animateTo(target, tween(if (preferences.mode == "adaptive") 800 else 100))
        }
    }
    return brightness.value
}

/** 页面或弹窗只在拥有前台焦点的生命周期内覆盖亮度，交接和退出均恢复原窗口值。 */
@Composable internal fun ApplyReaderWindowBrightness(window: Window?) {
    val value = LocalReaderBrightness.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(value)
    var owning by remember(window, lifecycle) { mutableStateOf(false) }
    DisposableEffect(window, lifecycle) {
        var previous: Float? = null
        fun apply() {
            if (window == null || owning) return
            previous = window.attributes.screenBrightness
            owning = true
            window.attributes = window.attributes.apply { screenBrightness = latest }
        }
        fun restore() {
            if (window != null && owning) window.attributes = window.attributes.apply { screenBrightness = previous ?: -1f }
            owning = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply()
                Lifecycle.Event.ON_PAUSE -> restore()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) apply()
        onDispose { lifecycle.removeObserver(observer); restore() }
    }
    SideEffect { if (window != null && owning) window.attributes = window.attributes.apply { screenBrightness = value } }
}

@Composable internal fun ApplyReaderDialogBrightness() {
    ApplyReaderWindowBrightness((LocalView.current.parent as? DialogWindowProvider)?.window)
}
