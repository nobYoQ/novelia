package cc.novelia.app.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReaderStatusItems
import cc.novelia.app.reader.safeFraction
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import java.util.Date
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class ReaderBarState(val bookTitle: String, val chapterTitle: String, val progress: Float,
    val pageReady: Boolean, val pageCount: Int, val pageIndex: Int, val visiblePageCount: Int = 1)

internal fun Density.readerBarHeight(rows: Int) = if(rows == 0) 0.dp else 18.sp.toDp() * rows + 12.dp + 4.dp * (rows - 1)

internal data class ReaderBatteryLevel(val percent: Int?, val charging: Boolean) {
    val icon: ImageVector get() = when {
        charging -> Icons.Outlined.BatteryChargingFull
        percent == null -> Icons.Outlined.BatteryUnknown
        percent < 15 -> Icons.Outlined.BatteryAlert
        percent < 40 -> Icons.Outlined.Battery2Bar
        percent < 75 -> Icons.Outlined.Battery4Bar
        else -> Icons.Outlined.BatteryFull
    }
}

internal class ReaderBarDevices(val clock: State<String>?, val battery: State<ReaderBatteryLevel>?)

@Composable internal fun rememberReaderBarDevices(items: List<String>): ReaderBarDevices {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = if("time" in items) produceState(remember(context) { DateFormat.getTimeFormat(context).format(Date()) }, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                value = DateFormat.getTimeFormat(context).format(Date())
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    } else null
    val battery = if("battery" in items) produceState(ReaderBatteryLevel(null, false), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            fun update(intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                value = ReaderBatteryLevel(if(level >= 0 && scale > 0) (level.toLong() * 100 / scale).coerceIn(0, 100).toInt() else null,
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
            }
            val sticky = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try { update(sticky); awaitCancellation() } finally { context.unregisterReceiver(receiver) }
        }
    } else null
    return remember(clock, battery) { ReaderBarDevices(clock, battery) }
}

@Composable internal fun ReaderReadingStatusBar(
    state: ReaderBarState, settings: ReaderSettings, columns: Int, foreground: Color, modifier: Modifier = Modifier,
    top: Boolean = false, devices: ReaderBarDevices? = null, tagPrefix: String = if(top) "reader-top-" else "reader-",
    onChapterTitle: (() -> Unit)? = null,
) {
    val items = if(top) settings.visibleTopReadingStatusItems else settings.visibleReadingStatusItems
    if(items.isEmpty()) return
    val indicators = devices ?: rememberReaderBarDevices(items)
    val rowHeight = with(LocalDensity.current) { 18.sp.toDp() }
    val animateBattery = !settings.eInkMode && !appReducedMotion()
    Column(modifier.testTag("${tagPrefix}reading-status-bar").padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReaderStatusItems.layout(items, columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if(row.any(ReaderStatusItems::isTitle)) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween) {
                row.forEach { item -> key(item) {
                    val cell = if(ReaderStatusItems.isTitle(item)) Modifier.weight(1f) else Modifier
                    when(item) {
                        "time" -> StatusField(indicators.clock?.value.orEmpty(), Icons.Outlined.Schedule, foreground, cell.testTag("${tagPrefix}status-clock"))
                        "battery" -> indicators.battery?.value?.let { level ->
                            val text = level.percent?.let { "$it%" } ?: "—"
                            StatusField(text, level.icon, foreground, cell.testTag("${tagPrefix}status-battery"), animate = animateBattery,
                                description = if(level.percent == null) "电量未知" else if(level.charging) "正在充电，电量 $text" else "电量 $text")
                        }
                        "book" -> StatusField(state.bookTitle, Icons.Outlined.AutoStories, foreground, cell.testTag("${tagPrefix}status-book-title"))
                        "chapter" -> StatusField(state.chapterTitle, Icons.Outlined.BookmarkBorder, foreground, cell.testTag("${tagPrefix}status-chapter-title")
                            .then(if(onChapterTitle != null) Modifier.clickable(onClickLabel = "查看完整章节名", onClick = onChapterTitle) else Modifier))
                        "pages" -> {
                            val total = state.pageCount.coerceAtLeast(1)
                            val current = (state.pageIndex + 1).coerceIn(1, total)
                            val last = (state.pageIndex + state.visiblePageCount).coerceIn(current, total)
                            val range = if(current == last) "$current" else "$current–$last"
                            StatusField(if(state.pageReady) "$range / $total" else "正在分页…", Icons.Outlined.Layers, foreground,
                                cell.testTag("${tagPrefix}page-counter"), description = if(state.pageReady) "本章第${range}页，共${total}页" else null)
                        }
                        "progress" -> {
                            val total = state.pageCount.coerceAtLeast(1)
                            val percent = if(settings.staticPagination) ((state.pageIndex + state.visiblePageCount).coerceIn(1, total).toLong() * 100 / total).toInt()
                                else (state.progress.safeFraction() * 100).toInt()
                            StatusField(if(settings.staticPagination && !state.pageReady) "本章 —" else "本章 $percent%", Icons.Outlined.Timeline,
                                foreground, cell.testTag("${tagPrefix}chapter-progress"))
                        }
                    }
                } }
            }
        }
    }
}

@Composable private fun StatusField(text: String, icon: ImageVector, foreground: Color, modifier: Modifier,
    animate: Boolean = false, description: String? = null) {
    Row(modifier.semantics(mergeDescendants = true) { if(description != null) contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val glyph: @Composable (ImageVector) -> Unit = { Icon(it, null, Modifier.size(14.dp), tint = foreground.copy(alpha = .8f)) }
        if(animate) Crossfade(icon, animationSpec = tween(AppMotion.Quick), label = "电量状态", content = glyph) else glyph(icon)
        Text(text, color = foreground, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
