package cc.novelia.app.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.theme.LocalReaderBrightness
import cc.novelia.app.ui.theme.LocalReaderBrightnessPreview
import cc.novelia.app.ui.theme.hasReaderLightSensor
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlin.math.roundToInt

@Composable internal fun ReaderDisplayPreferences(settings: ReaderSettings, onEditPreset: () -> Unit,
    onChange: (ReaderSettings) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().toggleable(settings.nightReading, role = Role.Switch,
                onValueChange = { onChange(settings.copy(nightReading = it)) }).testTag("reader-night-toggle").padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.NightsStay, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("夜间阅读", style = MaterialTheme.typography.titleSmall)
                    Text(if(settings.nightReading) "已开启 · 关闭后恢复普通配置" else "未开启 · 正在使用普通配置",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.nightReading, onCheckedChange = null)
            }
            ReaderNightPresetEntry(settings, onEditPreset)
        }
    }
    ReaderPreferenceSection("屏幕亮度", if(settings.nightReading) "正在调整夜间亮度 · 自动保存到夜间预设" else "正在调整普通亮度 · 自动保存", Icons.Outlined.Brightness6) {
        val preview = LocalReaderBrightnessPreview.current
        val latestPreview by rememberUpdatedState(preview)
        DisposableEffect(settings.nightReading) { onDispose { latestPreview(null) } }
        key(settings.nightReading) {
            ReaderBrightnessControls(settings.activeBrightness, onPreview = preview) { next ->
                preview(null)
                onChange(settings.withBrightness(next))
            }
        }
    }
}

@Composable private fun ReaderNightPresetEntry(settings: ReaderSettings, onClick: () -> Unit) {
    val builtIn = settings.nightPreset == ReaderNightPreset() && settings.nightBrightness == ReaderBrightness.night()
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).testTag("reader-edit-night-preset")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("编辑夜间预设", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if(builtIn) "内置" else "自定义", style = MaterialTheme.typography.labelSmall)
                }
                Text(if(settings.nightReading) "亮度、配色、排版 · 保存后应用" else "可先调整预设，再开启夜间阅读", style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

/** 快速调节与预设编辑共用控件；自动调节范围默认收起，主滑条只表达偏暗／偏亮。 */
@Composable internal fun ReaderBrightnessControls(preferences: ReaderBrightness,
    onPreview: (ReaderBrightness?) -> Unit, onChange: (ReaderBrightness) -> Unit) {
    val lightSensor = hasReaderLightSensor()
    var draft by remember(preferences) { mutableStateOf(preferences) }
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    fun show(next: ReaderBrightness) { draft = next; onPreview(next) }
    fun save(next: ReaderBrightness) { draft = next; onChange(next) }
    ChoiceRow("调节方式", listOf("跟随系统", "手动", "自动"), ReaderBrightness.MODES.indexOf(preferences.mode)) {
        onPreview(null)
        save(preferences.copy(mode = ReaderBrightness.MODES[it]))
    }
    val actual = LocalReaderBrightness.current
    val message = when {
        preferences.mode == "system" -> "由系统控制亮度，包括系统的自动亮度设置。"
        preferences.mode == "adaptive" && !lightSensor -> "此设备无法感应环境光，暂时使用下方的备用亮度。"
        preferences.mode == "adaptive" -> "根据环境光自动调节。觉得太暗或太亮时，调整下方偏好。"
        else -> "拖动滑条可预览亮度，松手后保留当前数值。"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("reader-brightness-status"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(preferences.mode != "system" && actual != null && actual >= 0) Text("当前屏幕约 ${(actual * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    if(preferences.mode == "manual" || preferences.mode == "adaptive" && !lightSensor) {
        ReaderSlider("${if(preferences.mode == "adaptive") "备用亮度" else "亮度"} ${(draft.level * 100).roundToInt()}%", preferences.level, .01f..1f,
            modifier = Modifier.testTag("reader-screen-brightness"), onPreview = { show(preferences.copy(level = it)) }) {
            save(preferences.copy(level = it))
        }
    }
    if(preferences.mode == "adaptive" && lightSensor) {
        ReaderSlider("舒适亮度 · ${when { draft.adjustment < -.01f -> "偏暗"; draft.adjustment > .01f -> "偏亮"; else -> "标准" }}",
            preferences.adjustment, -0.5f..0.5f, modifier = Modifier.testTag("reader-brightness-adjustment"),
            onPreview = { show(preferences.copy(adjustment = it)) }) { save(preferences.copy(adjustment = it)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("偏暗", "标准", "偏亮").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(onClick = { rangeExpanded = !rangeExpanded }, shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("reader-brightness-range-toggle")) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("自动调节范围", style = MaterialTheme.typography.labelLarge)
                    Text("${(preferences.minimum * 100).roundToInt()}% – ${(preferences.maximum * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Text(if(rangeExpanded) "收起" else "调整", style = MaterialTheme.typography.labelMedium)
                Icon(if(rangeExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
        }
        if(rangeExpanded) {
            Text("环境再暗也不低于下限，再亮也不超过上限。", Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReaderSlider("最低亮度 ${(draft.minimum * 100).roundToInt()}%", preferences.minimum, .01f..1f,
                modifier = Modifier.testTag("reader-brightness-minimum"),
                onPreview = { show(preferences.copy(minimum = it, maximum = maxOf(it, preferences.maximum))) }) {
                save(preferences.copy(minimum = it, maximum = maxOf(it, preferences.maximum)))
            }
            ReaderSlider("最高亮度 ${(draft.maximum * 100).roundToInt()}%", preferences.maximum, .01f..1f,
                modifier = Modifier.testTag("reader-brightness-maximum"),
                onPreview = { show(preferences.copy(maximum = it, minimum = minOf(it, preferences.minimum))) }) {
                save(preferences.copy(maximum = it, minimum = minOf(it, preferences.minimum)))
            }
        }
    }
}
