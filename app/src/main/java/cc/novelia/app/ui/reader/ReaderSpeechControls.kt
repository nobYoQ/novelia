@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.SpeechPlayback
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appRoundedCornerShape

/** 播放中的操作留在正文旁；弹层只承担预览与朗读选项。 */
@Composable internal fun ReaderSpeechSheet(
    settings: ReaderSettings, speechStatus: String, onDismiss: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onStop: () -> Unit, onSettings: () -> Unit,
    playback: SpeechPlayback? = null, onPrevious: () -> Unit = {}, onNext: () -> Unit = {}, onFollow: (Boolean) -> Unit = {},
    sample: String = "", onRate: (Float) -> Unit = {}, onTimer: (Int) -> Unit = {},
) {
    val preparing = speechStatus == "正在准备朗读…" || speechStatus == "正在准备下一章…"
    ReaderSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(modifier = Modifier.fillMaxWidth().testTag("reader-speech-panel"),
            contentModifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = appRoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.Headphones, null, Modifier.padding(12.dp).size(26.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("系统朗读", style = MaterialTheme.typography.titleLarge)
                    Text("边听边读，句子切换就在正文下方", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if(playback != null) "第 ${(playback.index + 1).coerceAtMost(playback.count)} / ${playback.count} 句" else "从当前段落开始",
                        Modifier.testTag("reader-speech-sentence"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(playback?.segment?.text ?: sample.ifBlank { "打开朗读后，正文会高亮当前句，并自动滚动或翻页。" },
                        maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    if(speechStatus.isNotBlank()) Text(speechStatus, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(playback != null) {
                ReaderSpeechTransport(playback, preparing, onPrevious, onNext, onPause, onResume, onStop)
                AppButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("边听边读")
                }
                TogglePreference("跟随朗读位置", "自动定位当前句；手动翻阅后可在底栏恢复跟随", playback.following, onFollow)
            } else AppButton(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !preparing) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if(preparing) "正在准备朗读…" else "从这里开始朗读")
            }
            SpeechOptionCard("语速", "调整朗读速度，并保存为阅读偏好") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                        AppSelectionChip(settings.speechRate == rate, { onRate(rate) }, label = { Text("${rate}×") })
                    }
                }
            }
            SpeechOptionCard("定时停止", if(playback == null) "从开始朗读时计时" else "选择后从现在重新计时") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        AppSelectionChip(settings.speechMinutes == minutes, { onTimer(minutes) }, label = { Text("$minutes 分钟") })
                    }
                }
            }
            Text(if(settings.speechContinueChapters) "连续听书已开启 · ${if(settings.speechNetworkContinuation) "未缓存章节允许联网加载" else "仅朗读本地与缓存章节"}" else "朗读至本章结束 · 连续听书可在偏好中开启",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppTextButton(onClick = onSettings) { Text("语言与连续听书") }
                if(playback != null) AppTextButton(onClick = onStart, enabled = !preparing) { Text("从当前段重读") }
            }
        }
    }
}

@Composable private fun SpeechOptionCard(title: String, detail: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** 嵌在阅读底栏上方；底部安全区由底栏统一处理。 */
@Composable internal fun ReaderSpeechDock(playback: SpeechPlayback, speechStatus: String,
    onPrevious: () -> Unit, onNext: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onStop: () -> Unit, onFollow: (Boolean) -> Unit, onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(modifier.fillMaxWidth().testTag("reader-speech-dock"), color = containerColor, contentColor = contentColor,
        tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${if(playback.playing) "正在朗读" else "朗读已暂停"} · ${playback.index + 1}/${playback.count}",
                        style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playback.segment?.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
                AppTextButton(onClick = { onFollow(!playback.following) }, modifier = Modifier.testTag("reader-speech-follow"),
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
                    Text(if(playback.following) "跟随中" else "恢复跟随")
                }
                AppIconButton(onClick = onExpand) { Icon(Icons.Outlined.Tune, "朗读选项") }
            }
            ReaderSpeechTransport(playback, speechStatus == "正在准备下一章…", onPrevious, onNext, onPause, onResume, onStop)
        }
    }
}

@Composable private fun ReaderSpeechTransport(playback: SpeechPlayback, preparing: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppFilledTonalButton(onClick = onPrevious, enabled = !preparing && playback.index in 1 until playback.count,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(Icons.Outlined.SkipPrevious, "上一句")
        }
        AppButton(onClick = if(playback.playing || preparing) onPause else onResume,
            modifier = Modifier.weight(1.4f).heightIn(min = 52.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(if(playback.playing || preparing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
            Spacer(Modifier.width(4.dp)); Text(if(playback.playing || preparing) "暂停" else "继续", maxLines = 1)
        }
        AppFilledTonalButton(onClick = onNext, enabled = !preparing && playback.index + 1 < playback.count,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(Icons.Outlined.SkipNext, "下一句")
        }
        AppIconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, "停止朗读") }
    }
}
