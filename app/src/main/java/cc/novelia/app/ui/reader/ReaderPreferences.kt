@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.TogglePreference
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import kotlin.math.roundToInt

@Composable fun ReaderPreferences(value: ReaderSettings, perBook: Boolean? = null, onPerBook: (Boolean) -> Unit = {}, onChange: (ReaderSettings) -> Unit) {
    val reducedMotion = appReducedMotion()
    AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
        Text("阅读偏好", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if(perBook != null) TogglePreference("仅应用于这本书", "为当前小说保存独立设置", perBook, onPerBook)
        ReaderPreferenceHeading("常用设置")
        ReaderSlider("字号 ${value.fontSize.toInt()}", value.fontSize, 14f..32f) { onChange(value.copy(fontSize = it)) }
        ReaderSlider("行距 ${"%.1f".format(value.lineHeight)}", value.lineHeight, 1.3f..2.6f) { onChange(value.copy(lineHeight = it)) }
        ChoiceRow("阅读主题", listOf("跟随应用", "纸张", "浅色", "深色", "黑白"), listOf("system", "paper", "light", "dark", "monochrome").indexOf(value.resolvedTheme)) { onChange(value.withTheme(listOf("system", "paper", "light", "dark", "monochrome")[it])) }
        TogglePreference("跟随系统亮度", "关闭后可单独调整", value.brightness < 0) { onChange(value.copy(brightness = if(it) -1f else .5f)) }
        AnimatedVisibility(value.brightness >= 0,
            enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + expandVertically(tween(AppMotion.Standard), expandFrom = Alignment.Top),
            exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + shrinkVertically(tween(AppMotion.Release), shrinkTowards = Alignment.Top)
        ) {
            ReaderSlider("屏幕亮度", value.brightness.coerceIn(.05f, 1f), .05f..1f, enabled = value.brightness >= 0) { onChange(value.copy(brightness = it)) }
        }
        ReaderPreferenceHeading("翻页与工具栏")
        TogglePreference("电子纸阅读模式", "首次开启使用自动分页。作为默认偏好时，全应用改为按屏翻动和按钮排序；仅应用于这本书时只影响当前阅读器。保留主题，关闭后恢复普通交互。", value.eInkMode) { onChange(value.withEInkMode(it)) }
        ChoiceRow("分页模式", listOf("连续滚动", "自动分页"), if(value.staticPagination) 1 else 0) { onChange(value.withPaginationMode(if(it == 1) "auto" else "scroll")) }
        Text(if(value.staticPagination) "按屏幕大小提前排成独立页面，每次翻动一页。" else "整章连续排列，上下滑动浏览，不提前拆成独立页面。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(value.staticPagination) Column(Modifier.padding(start = 20.dp, end = 12.dp)) {
            Text("自动分页手势", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            TogglePreference("滚动翻页", "向上滑动下一页，向下滑动上一页", value.scrollPageTurn) { onChange(value.copy(scrollPageTurn = it)) }
            TogglePreference("左右翻页", "向左滑动下一页，向右滑动上一页", value.horizontalPageTurn) { onChange(value.copy(horizontalPageTurn = it)) }
        }
        TogglePreference("显示翻页按钮", if(value.staticPagination) "显示上一页、下一页按钮" else "显示上一屏、下一屏，每次移动约一屏正文", value.showPageButtons) { onChange(value.copy(showPageButtons = it)) }
        ReaderSlider("工具栏透明度 ${(value.resolvedToolbarTransparency * 100).roundToInt()}%", value.resolvedToolbarTransparency, 0f..1f,
            modifier = Modifier.testTag("reader-toolbar-transparency")) { onChange(value.copy(toolbarTransparency = it)) }
        Text("0% 为不透明，100% 为背景完全透明；文字和图标保持清晰。工具栏覆盖正文，显示或收起不会改变排版。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReaderPreferenceHeading("语言与离线预读")
        ChoiceRow("显示语言", listOf("中文", "日文", "中日", "日中"), listOf("zh", "jp", "zh-jp", "jp-zh").indexOf(value.mode)) { onChange(value.copy(mode = listOf("zh", "jp", "zh-jp", "jp-zh")[it])) }
        ChoiceRow("优先译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(value.engines.firstOrNull())) { val engine = listOf("sakura", "gpt", "youdao")[it]; onChange(value.copy(engines = listOf(engine) + value.engines.filterNot { e -> e == engine })) }
        TogglePreference("并列展示译文", "关闭时按优先顺序回退", value.parallel) { onChange(value.copy(parallel = it)) }
        ChoiceRow("自动预读后续章节", listOf("关闭", "1 章", "3 章", "5 章"), listOf(0, 1, 3, 5).indexOf(value.prefetchChapters).coerceAtLeast(0)) { onChange(value.copy(prefetchChapters = listOf(0, 1, 3, 5)[it])) }
        TogglePreference("仅 Wi-Fi 自动预读", "每次进入章节后依次预读，退出阅读或清理缓存即停止", value.prefetchWifiOnly) { onChange(value.copy(prefetchWifiOnly = it)) }
        ReaderPreferenceHeading("进阶排版与屏幕")
        ReaderSlider("内容宽度 ${value.width.toInt()} dp", value.width, 300f..900f) { onChange(value.copy(width = it)) }
        ReaderSlider("辅文本不透明度 ${(value.secondaryAlpha * 100).toInt()}%", value.secondaryAlpha, .45f..1f) { onChange(value.copy(secondaryAlpha = it)) }
        TogglePreference("加粗文字", "两种阅读模式使用一致的粗体", value.weight) { onChange(value.copy(weight = it)) }
        TogglePreference("首行缩进", "统一为两个全角空格", value.indent) { onChange(value.copy(indent = it)) }
        TogglePreference("繁体显示", "将简体译文转换为繁体", value.traditional) { onChange(value.copy(traditional = it)) }
        TogglePreference("辅文本下划线", "用于双语对照", value.underline) { onChange(value.copy(underline = it)) }
        TogglePreference("屏幕常亮", "仅在阅读器中生效", value.keepScreenOn) { onChange(value.copy(keepScreenOn = it)) }
        TogglePreference("音量键翻页", "音量键控制阅读位置", value.volumeKeys) { onChange(value.copy(volumeKeys = it)) }
        ReaderPreferenceHeading("系统朗读")
        ReaderSlider("朗读速度 ${"%.1f".format(value.speechRate)}×", value.speechRate, .5f..2f) { onChange(value.copy(speechRate = it)) }
        ChoiceRow("朗读语言", listOf("随显示模式", "中文", "日文"), listOf("auto", "zh", "jp").indexOf(value.speechLanguage)) { onChange(value.copy(speechLanguage = listOf("auto", "zh", "jp")[it])) }
        ChoiceRow("朗读定时停止", listOf("15 分钟", "30 分钟", "60 分钟"), listOf(15, 30, 60).indexOf(value.speechMinutes)) { onChange(value.copy(speechMinutes = listOf(15, 30, 60)[it])) }
    }
}
@Composable private fun ReaderPreferenceHeading(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
@Composable private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if(LocalEInkMode.current) {
            val step = when { range.endInclusive - range.start > 100f -> 20f; range.endInclusive - range.start > 10f -> 1f; else -> .05f }
            Row(modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                if(!enabled) disabled()
                setProgress { next -> if(enabled && next.isFinite()) { onChange(next.coerceIn(range)); true } else false }
            }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled && value > range.start) { Icon(Icons.Outlined.Remove, "减小 $label") }
                Text("${(value * 100).roundToInt() / 100f}", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled && value < range.endInclusive) { Icon(Icons.Outlined.Add, "增大 $label") }
            }
        } else {
            // Keep thumb feedback immediate while committing expensive typography changes once.
            var draft by remember(value) { mutableFloatStateOf(value) }
            Slider(draft, { draft = it }, modifier = modifier, valueRange = range, enabled = enabled,
                onValueChangeFinished = { onChange(draft) })
        }
    }
}
