package cc.novelia.app.ui.feedback

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A finite accent that rests after one gesture; it never resumes an interrupted animation. */
@Composable
fun StickerAccent(sticker: MidoriSticker, trigger: Any? = Unit, modifier: Modifier = Modifier) {
    val motion = stickerMotionEnabled()
    var resumed by remember { mutableStateOf(false) }
    var played by remember(sticker, trigger) { mutableStateOf(false) }
    val progress = remember(sticker, trigger) { Animatable(1f) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(sticker, trigger, motion, resumed) {
        progress.snapTo(1f)
        if(!resumed || played) return@LaunchedEffect
        played = true
        if(motion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(if(sticker == MidoriSticker.Sleep) AppMotion.StickerRest else AppMotion.Sticker, easing = LinearEasing))
        }
    }
    MidoriIllustration(sticker, modifier.graphicsLayer {
        val p = if(motion && resumed) progress.value else 1f
        val arc = sin(p * PI).toFloat()
        transformOrigin = TransformOrigin(.5f, .8f)
        when(sticker) {
            MidoriSticker.Wave -> rotationZ = sin(p * PI * 6).toFloat() * (1f - p) * 10f
            MidoriSticker.Concerned, MidoriSticker.Curious -> rotationZ = sin(p * PI * 4).toFloat() * (1f - p) * 5f
            MidoriSticker.Sleep -> { scaleX = 1f + arc * .045f; scaleY = scaleX }
            else -> {
                translationY = -arc * 7.dp.toPx()
                scaleX = 1f + arc * .09f
                scaleY = scaleX
            }
        }
    })
}

@Composable
internal fun AboutIdentity() {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var wave by remember { mutableIntStateOf(0) }
    var waving by remember { mutableStateOf(false) }
    LaunchedEffect(wave) {
        if(wave > 0) { waving = true; delay(2400); waving = false }
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp)) {
            if(waving) StickerAccent(MidoriSticker.Wave, wave, Modifier.fillMaxSize().semantics { contentDescription = "小绿向你招手" })
            else Icon(Icons.Outlined.AutoStories, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary)
        }
        Text("Novelia", style = MaterialTheme.typography.headlineLarge)
        Text("让每个故事，随身同行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "版本信息") {
            val now = SystemClock.uptimeMillis()
            taps = if(now - lastTap < 800) taps + 1 else 1
            lastTap = now
            if(taps >= 5) { taps = 0; wave++ }
        }, contentAlignment = Alignment.CenterStart) {
            Text("Android ${BuildConfig.VERSION_NAME} · 非官方客户端", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal data class StickerSnackbarVisuals(
    override val message: String,
    val sticker: MidoriSticker,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = true,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

@Composable
fun StickerSnackbarHost(state: SnackbarHostState) {
    val render: @Composable (SnackbarData) -> Unit = { data ->
        val visuals = data.visuals
        if(visuals is StickerSnackbarVisuals) {
            Snackbar(
                modifier = Modifier.padding(12.dp).heightIn(min = 80.dp),
                action = visuals.actionLabel?.let { label -> ({ TextButton(onClick = data::performAction) { Text(label) } }) },
                dismissAction = if(visuals.withDismissAction) ({ IconButton(onClick = data::dismiss) { Icon(Icons.Outlined.Close, "关闭提示") } }) else null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StickerAccent(visuals.sticker, data, Modifier.size(56.dp))
                    Text(visuals.message, Modifier.weight(1f))
                }
            }
        } else Snackbar(data)
    }
    if(!appReducedMotion()) SnackbarHost(state, snackbar = render)
    else {
        val current = state.currentSnackbarData
        val accessibility = LocalAccessibilityManager.current
        LaunchedEffect(current) {
            if(current != null && current.visuals.duration != SnackbarDuration.Indefinite) {
                val duration = if(current.visuals.duration == SnackbarDuration.Long) 10_000L else 4_000L
                delay(accessibility?.calculateRecommendedTimeoutMillis(duration, containsIcons = true,
                    containsText = true, containsControls = current.visuals.actionLabel != null || current.visuals.withDismissAction) ?: duration)
                current.dismiss()
            }
        }
        if(current != null) Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { render(current) }
    }
}

internal fun newlyCompletedDownloads(previous: Map<String, String>, current: List<DownloadEntry>): List<DownloadEntry> =
    current.filter { it.status == "已完成" && previous[it.id]?.let { old -> old != "已完成" } == true }

@Composable
fun ObserveDownloadCelebrations(c: AppController, route: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRoute by rememberUpdatedState(route)
    LaunchedEffect(c, lifecycle) {
        var previous = c.store.state.value.downloads.associate { it.id to it.status }
        c.store.state.map { it.downloads }.distinctUntilChanged().collect { entries ->
            val finished = newlyCompletedDownloads(previous, entries)
            previous = entries.associate { it.id to it.status }
            // Background completions stay in download history; do not replay celebrations on return.
            if(finished.isNotEmpty() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && latestRoute?.startsWith("reader/") != true) {
                c.celebrate(if(finished.size == 1) "「${finished.single().title}」下载完成" else "${finished.size} 本小说下载完成", MidoriSticker.Celebrate)
            }
        }
    }
}
