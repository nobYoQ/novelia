package cc.novelia.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.SpeechPlayback
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder
import java.util.Locale

@Composable internal fun ReaderSpeechSheet(
    settings: ReaderSettings, speechStatus: String, onDismiss: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onStop: () -> Unit, onSettings: () -> Unit,
    playback: SpeechPlayback? = null, onPrevious: () -> Unit = {}, onNext: () -> Unit = {}, onFollow: (Boolean) -> Unit = {},
    sample: String = "", onRate: (Float) -> Unit = {}, onTimer: (Int) -> Unit = {},
    onEngineSettings: (() -> Unit)? = null,
) {
    val preparing = speechStatus == "正在准备朗读…" || speechStatus == "正在准备下一章…"
    val colors = MaterialTheme.colorScheme
    val excerpt = playback?.segment?.text ?: sample
    ReaderSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(modifier = Modifier.fillMaxWidth().testTag("reader-speech-panel"),
            contentModifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("系统朗读", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                // 静态弹层由 ReaderSheet 提供关闭按钮。
                if (!appReducedMotion()) AppIconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "关闭朗读面板")
                }
            }
            Surface(shape = appRoundedCornerShape(24.dp), color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer, border = monochromeBorder()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (speechStatus.isNotBlank() || excerpt.isNotBlank() || playback != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Headphones, null, Modifier.size(20.dp))
                            Text(speechStatus.removePrefix("正在朗读：").ifBlank {
                                when {
                                    playback == null -> "当前段落"
                                    playback.playing -> "正在朗读"
                                    else -> "朗读已暂停"
                                }
                            }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (playback != null) {
                                AppIconButton(onClick = onStart, enabled = !preparing) {
                                    Icon(Icons.Outlined.Replay, "从当前段重读")
                                }
                                AppIconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, "停止朗读") }
                            }
                        }
                    }
                    if (excerpt.isNotBlank()) Text(excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    if (playback == null) {
                        AppButton(onClick = onStart, enabled = !preparing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("从这里开始朗读")
                        }
                    } else {
                        if (playback.count > 0) {
                            val position = (playback.index + 1).coerceIn(1, playback.count)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppLinearProgressIndicator(progress = { position.toFloat() / playback.count },
                                    modifier = Modifier.weight(1f), color = colors.onPrimaryContainer,
                                    trackColor = colors.onPrimaryContainer.copy(alpha = .18f))
                                Text("第 $position / ${playback.count} 句", Modifier.testTag("reader-speech-sentence"),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        ReaderSpeechTransport(playback, preparing, onPrevious, onNext, onPause, onResume)
                    }
                }
            }
            SpeechQuickSettings(settings, onRate, onTimer)
            Surface(shape = appRoundedCornerShape(16.dp), color = colors.surface, border = monochromeBorder()) {
                Column {
                    if (playback != null) {
                        TogglePreference("跟随朗读位置", "", playback.following, onFollow)
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                    val continuation = when {
                        !settings.speechContinueChapters -> "仅本章"
                        settings.speechNetworkContinuation -> "连续朗读 · 允许联网"
                        else -> "连续朗读 · 仅本地与缓存"
                    }
                    SpeechSettingsLink("语言与连续听书", Icons.Outlined.Translate, onSettings, continuation)
                    if (onEngineSettings != null) {
                        HorizontalDivider(color = colors.outlineVariant)
                        SpeechSettingsLink("系统朗读引擎", Icons.Outlined.RecordVoiceOver, onEngineSettings)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SpeechQuickSettings(settings: ReaderSettings, onRate: (Float) -> Unit, onTimer: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minTileWidth = with(LocalDensity.current) { 96.sp.toDp() } + 40.dp
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (maxWidth >= minTileWidth * 2 + 8.dp) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeechOptionMenu("语速", settings.speechRate, listOf(.75f, 1f, 1.25f, 1.5f, 2f),
                label = { "%.2f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.') + "×" },
                onSelect = onRate, modifier = Modifier.weight(1f))
            SpeechOptionMenu("定时停止", settings.resolvedSpeechMinutes, ReaderSettings.SPEECH_TIMER_OPTIONS,
                label = ReaderSettings::speechTimerLabel, onSelect = onTimer, modifier = Modifier.weight(1f))
        }
    }
}

@Composable private fun <T> SpeechOptionMenu(title: String, value: T, choices: List<T>, label: (T) -> String,
    onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Surface(shape = appRoundedCornerShape(16.dp), color = colors.surfaceContainerLowest, border = monochromeBorder()) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = true }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                val valueHeight = with(LocalDensity.current) { 34.sp.toDp() }
                Text(label(value), Modifier.heightIn(min = valueHeight).wrapContentHeight(),
                    style = MaterialTheme.typography.titleLarge)
            }
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, modifier = Modifier.semantics { selected = option == value },
                    trailingIcon = if (option == value) ({ Icon(Icons.Outlined.Check, null) }) else null,
                    onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

@Composable private fun SpeechSettingsLink(title: String, icon: ImageVector, onClick: () -> Unit, value: String? = null) {
    AppListItem(headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        supportingContent = value?.let { { Text(it) } },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick), containerColor = Color.Transparent)
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
    onPrevious: () -> Unit, onNext: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: (() -> Unit)? = null,
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
        if (onStop != null) AppIconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, "停止朗读") }
    }
}
