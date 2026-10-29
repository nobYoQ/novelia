package cc.novelia.app.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.safeFraction
import java.util.Date
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 内容顺序属于阅读偏好；分行数仅随宽度、字体及配置改变，不随时间和进度改变。 */
@Composable internal fun ReaderReadingStatusBar(
    state: ReaderBottomToolbarState, settings: ReaderSettings, columns: Int, foreground: Color, modifier: Modifier = Modifier,
) {
    val items = settings.visibleReadingStatusItems
    val rowHeight = with(LocalDensity.current) { 16.sp.toDp() }
    Column(modifier.testTag("reader-reading-status-bar").padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEachIndexed { index, item ->
                    val align = when(index) { 0 -> TextAlign.Start; row.lastIndex -> TextAlign.End; else -> TextAlign.Center }
                    val cell = Modifier.weight(1f)
                    when(item) {
                        "time" -> ReaderClock(foreground, cell, align)
                        "battery" -> ReaderBattery(foreground, cell, align)
                        "chapter" -> StatusText(state.chapterTitle, foreground, cell.testTag("reader-status-chapter-title"), align)
                        "pages" -> if(!state.pageReady) StatusText("正在分页…", foreground, cell, align)
                            else {
                                val total = state.pageCount.coerceAtLeast(1)
                                val current = (state.pageIndex + 1).coerceIn(1, total)
                                StatusText("$current / $total", foreground, cell.testTag("reader-page-counter").semantics {
                                    contentDescription = "本章第${current}页，共${total}页"
                                }, align)
                            }
                        "progress" -> {
                            val percent = if(settings.staticPagination) {
                                val total = state.pageCount.coerceAtLeast(1)
                                ((state.pageIndex + 1).coerceIn(1, total).toLong() * 100 / total).toInt()
                            } else (state.progress.safeFraction() * 100).toInt()
                            StatusText(if(settings.staticPagination && !state.pageReady) "本章 —" else "本章 $percent%",
                                foreground, cell.testTag("reader-chapter-progress"), align)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun StatusText(text: String, foreground: Color, modifier: Modifier, align: TextAlign) {
    Text(text, modifier, color = foreground, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = align)
}

/** 仅前台按分钟更新，不逐秒刷新电子纸。 */
@Composable private fun ReaderClock(foreground: Color, modifier: Modifier, align: TextAlign) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time by produceState(DateFormat.getTimeFormat(context).format(Date()), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                value = DateFormat.getTimeFormat(context).format(Date())
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    StatusText(time, foreground, modifier.testTag("reader-status-clock"), align)
}

/** 使用系统粘性广播，不申请权限；关闭该项目或离开前台即注销接收器。 */
@Composable private fun ReaderBattery(foreground: Color, modifier: Modifier, align: TextAlign) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val text by produceState("电量未知", context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            fun update(intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                value = if(level < 0 || scale <= 0) "电量未知" else
                    "${if(charging) "充电" else "电量"} ${(level.toLong() * 100 / scale).coerceIn(0, 100)}%"
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
            }
            val sticky = ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try { update(sticky); awaitCancellation() } finally { context.unregisterReceiver(receiver) }
        }
    }
    StatusText(text, foreground, modifier.testTag("reader-status-battery"), align)
}
