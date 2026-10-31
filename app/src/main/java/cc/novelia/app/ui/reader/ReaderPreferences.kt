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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import cc.novelia.app.ui.theme.appRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.components.base.TogglePreference
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import kotlin.math.roundToInt

/** 状态由弹层外部持有，避免对话框与底部弹层切换重建组合时丢失。 */
@Stable class ReaderPreferencesState internal constructor(
    internal val tab: MutableIntState,
    internal val expandedGroup: MutableState<String>,
    internal val scrollStates: List<ScrollState>
)

@Composable fun rememberReaderPreferencesState(open: Boolean = true): ReaderPreferencesState = remember(open) {
    ReaderPreferencesState(mutableIntStateOf(0), mutableStateOf(""), List(4) { ScrollState(0) })
}

@Composable fun ReaderPreferences(value: ReaderSettings, perBook: Boolean? = null, onPerBook: (Boolean) -> Unit = {},
    state: ReaderPreferencesState = rememberReaderPreferencesState(), modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {}, livePreview: Boolean = false,
    defaultSettings: ReaderSettings? = null,
    onEditTapZones: (() -> Unit)? = null, onShowTapTutorial: (() -> Unit)? = null,
    onTextReplacements: (() -> Unit)? = null,
    onChange: (ReaderSettings) -> Unit) {
    val reducedMotion = appReducedMotion()
    val defaults = defaultSettings.takeIf { perBook == true }
    var tab by state.tab
    var previewZones by remember { mutableStateOf(false) }
    var statusBarExpanded by rememberSaveable { mutableStateOf(false) }
    if(previewZones) ReaderTapZonePreview(value, onSave = {
        onChange(value.copy(tapZoneMap = ReaderTapZoneMap.encode(it)))
        previewZones = false
    }, onDismiss = { previewZones = false })
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("阅读偏好", Modifier.weight(1f).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
            headerActions()
        }
        Text(if(perBook == true) "仅修改本书" else "正在修改默认设置",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("reader-preference-scope"),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        PrimaryTabRow(tab) {
            listOf("常用", "翻页", "界面", "更多").forEachIndexed { index, title ->
                Tab(tab == index, { tab = index }, modifier = Modifier.heightIn(min = 48.dp), text = { Text(title) })
            }
        }
        key(tab) {
            AppScrollColumn(modifier = Modifier.weight(1f, fill = false), state = state.scrollStates[tab], contentModifier = Modifier.padding(bottom = 20.dp)) {
                if(perBook != null) TogglePreference("仅应用于这本书", "关闭后跟随默认设置", perBook, onChange = onPerBook)
                if(defaults != null) Text("点击滑条默认进度或浅色选项可恢复",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                when(tab) {
                    0 -> {
                        ReaderPreferenceSection("文字与主题", "字体、间距与纸张颜色", Icons.Outlined.TextFields) {
                            ReaderFontPreference(value, defaults, onChange)
                            ReaderSlider("字号 ${value.fontSize.toInt()}", value.fontSize, 14f..32f,
                                defaultValue = defaults?.fontSize, livePreviewStep = if(livePreview) .5f else null) { onChange(value.copy(fontSize = it)) }
                            ReaderSlider("行距 ${"%.1f".format(value.lineHeight)}", value.lineHeight, ReaderSettings.LINE_HEIGHT_RANGE,
                                modifier = Modifier.testTag("reader-line-height"), defaultValue = defaults?.lineHeight, livePreviewStep = if(livePreview) .05f else null) { onChange(value.copy(lineHeight = it)) }
                            ReaderSlider("段距 ${value.resolvedParagraphSpacing.roundToInt()} dp", value.resolvedParagraphSpacing, 0f..32f,
                                modifier = Modifier.testTag("reader-paragraph-spacing"), defaultValue = defaults?.resolvedParagraphSpacing, livePreviewStep = if(livePreview) 1f else null) { onChange(value.copy(paragraphSpacing = it)) }
                            ChoiceRow("阅读主题", listOf("跟随应用", "纸张", "浅色", "深色", "黑白", "自定义"), ReaderSettings.THEMES.indexOf(value.resolvedTheme),
                                defaultSelected = defaults?.let { ReaderSettings.THEMES.indexOf(it.resolvedTheme) }) { onChange(value.withTheme(ReaderSettings.THEMES[it])) }
                            if(value.resolvedTheme == "custom") ReaderThemeEditor(value, defaults, onChange)
                        }
                        ReaderPreferenceSection("语言与译文", "原文与译文的显示顺序", Icons.Outlined.Translate) {
                            ChoiceRow("显示语言", listOf("中文", "日文", "中日", "日中"), listOf("zh", "jp", "zh-jp", "jp-zh").indexOf(value.mode),
                                defaultSelected = defaults?.let { listOf("zh", "jp", "zh-jp", "jp-zh").indexOf(it.mode) }) { onChange(value.copy(mode = listOf("zh", "jp", "zh-jp", "jp-zh")[it])) }
                            ChoiceRow("优先译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(value.engines.firstOrNull()),
                                defaultSelected = defaults?.let { listOf("sakura", "gpt", "youdao").indexOf(it.engines.firstOrNull()) }) { val engine = listOf("sakura", "gpt", "youdao")[it]; onChange(value.copy(engines = if(defaults?.engines?.firstOrNull() == engine) defaults.engines else listOf(engine) + value.engines.filterNot { e -> e == engine })) }
                            TogglePreference("并列展示译文", "关闭时按优先顺序回退", value.parallel, defaultValue = defaults?.parallel) { onChange(value.copy(parallel = it)) }
                            TogglePreference("繁体显示", "将简体译文转换为繁体", value.traditional, defaultValue = defaults?.traditional) { onChange(value.copy(traditional = it)) }
                        }
                    }
                    1 -> {
                        ReaderPreferenceSection("阅读模式", "选择适合屏幕的阅读方式", Icons.Outlined.AutoStories) {
                            TogglePreference("电子纸阅读模式", if(perBook == true) "仅影响当前阅读器；首次开启使用自动分页" else "全应用按屏翻动；首次开启使用自动分页，关闭后恢复原有交互", value.eInkMode, defaultValue = defaults?.eInkMode) { onChange(value.withEInkMode(it)) }
                        }
                        ReaderPreferenceSection("正文翻页", "分页、手势与快捷操作", Icons.Outlined.Swipe) {
                            ChoiceRow("分页模式", listOf("连续滚动", "自动分页"), if(value.staticPagination) 1 else 0, defaultSelected = defaults?.let { if(it.staticPagination) 1 else 0 }) { onChange(value.withPaginationMode(if(it == 1) "auto" else "scroll")) }
                            Text(if(value.staticPagination) "按屏幕大小提前排成独立页面，每次翻动一页。" else "整章连续排列，上下滑动浏览，不提前拆成独立页面。",
                                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TogglePreference("点击区域翻页", if(value.tapPageTurn) "在正文覆盖层调整区域大小，分别设置单击和双击动作" else "开启后可配置区域大小，以及翻页、目录和工具栏等点击动作",
                                value.tapPageTurn, defaultValue = defaults?.tapPageTurn) { onChange(value.copy(tapPageTurn = it)) }
                            if(value.tapPageTurn) {
                                val currentMap = value.resolvedTapZoneMap
                                AppTextButton(onClick = { if(onEditTapZones != null) onEditTapZones() else previewZones = true },
                                    modifier = Modifier.padding(horizontal = 12.dp).testTag("tap-zone-open-editor")) {
                                    Text(if(onEditTapZones != null) "在正文上配置点击区域" else "在示例正文上配置点击区域")
                                }
                                Text(currentMap.describe(), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if(onShowTapTutorial != null) AppTextButton(onClick = onShowTapTutorial,
                                    modifier = Modifier.padding(horizontal = 12.dp)) { Text("查看点击分区教程") }
                            }
                            if(value.staticPagination) {
                                TogglePreference("滚动翻页", "向上滑动下一页，向下滑动上一页", value.scrollPageTurn, defaultValue = defaults?.scrollPageTurn) { onChange(value.copy(scrollPageTurn = it)) }
                                TogglePreference("左右翻页", "向左滑动下一页，向右滑动上一页", value.horizontalPageTurn, defaultValue = defaults?.horizontalPageTurn) { onChange(value.copy(horizontalPageTurn = it)) }
                            }
                            TogglePreference("显示翻页按钮", if(value.staticPagination) "显示上一页、下一页按钮" else "显示上一屏、下一屏，每次移动约一屏正文", value.showPageButtons, defaultValue = defaults?.showPageButtons) { onChange(value.copy(showPageButtons = it)) }
                            TogglePreference("列表与面板翻屏按钮", "电子纸模式下显示目录、设置等面板的上一屏／下一屏；关闭后仍可滑动翻屏", value.showEInkScreenButtons, defaultValue = defaults?.showEInkScreenButtons) { onChange(value.copy(showEInkScreenButtons = it)) }
                            TogglePreference("音量键翻页", "音量键控制阅读位置", value.volumeKeys, defaultValue = defaults?.volumeKeys) { onChange(value.copy(volumeKeys = it)) }
                        }
                        ReaderPreferenceSection("滚动分页", "连续阅读时的章末操作", Icons.Outlined.UnfoldMore) {
                            TogglePreference("章节末尾按钮", "连续滚动时显示章末的下一章、下一分卷或返回目录按钮；关闭后仍可上拉进入下一章", value.showScrollPageButtons, defaultValue = defaults?.showScrollPageButtons) { onChange(value.copy(showScrollPageButtons = it)) }
                        }
                    }
                    2 -> {
                        ReaderPreferenceSection("屏幕", "亮度、方向与阅读时的显示方式", Icons.Outlined.Brightness6) {
                            TogglePreference("跟随系统亮度", "关闭后可单独调整", value.brightness < 0, defaultValue = defaults?.let { it.brightness < 0 }) { onChange(value.copy(brightness = if(it) -1f else defaults?.brightness?.takeIf { brightness -> brightness >= 0 } ?: .5f)) }
                            AnimatedVisibility(value.brightness >= 0,
                                enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + expandVertically(tween(AppMotion.Standard), expandFrom = Alignment.Top),
                                exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + shrinkVertically(tween(AppMotion.Release), shrinkTowards = Alignment.Top)
                            ) {
                                ReaderSlider("屏幕亮度", value.brightness.coerceIn(.05f, 1f), .05f..1f, enabled = value.brightness >= 0,
                                    defaultValue = defaults?.brightness?.takeIf { it >= 0 }) { onChange(value.copy(brightness = it)) }
                            }
                            ChoiceRow("阅读方向", listOf("跟随系统", "竖屏", "横屏"), ReaderSettings.ORIENTATIONS.indexOf(value.resolvedOrientation),
                                defaultSelected = defaults?.let { ReaderSettings.ORIENTATIONS.indexOf(it.resolvedOrientation) }) { onChange(value.copy(orientation = ReaderSettings.ORIENTATIONS[it])) }
                            Text("仅在阅读器中锁定方向，退出阅读后恢复跟随系统。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                            TogglePreference("宽屏显示目录", "平板和横屏时在正文旁显示目录，可随时收起", value.showWideToc,
                                defaultValue = defaults?.showWideToc) { onChange(value.copy(showWideToc = it)) }
                            TogglePreference("屏幕常亮", "仅在阅读器中生效", value.keepScreenOn, defaultValue = defaults?.keepScreenOn) { onChange(value.copy(keepScreenOn = it)) }
                        }
                        ReaderPreferenceSection("工具栏", "底栏信息、进度与透明度", Icons.Outlined.Tune) {
                            TogglePreference("底部阅读信息", "选择显示内容并调整顺序，关闭后保留配置", value.showReadingStatusBar, defaultValue = defaults?.showReadingStatusBar) { onChange(value.copy(showReadingStatusBar = it)) }
                            if(value.showReadingStatusBar) ReaderPreferenceGroup("底栏内容与顺序", value.resolvedReadingStatusItems.joinToString(" · ") { cc.novelia.app.data.model.ReaderStatusItems.labels.getValue(it) }.ifEmpty { "未选择内容" }, statusBarExpanded, { statusBarExpanded = !statusBarExpanded }) {
                                ReaderStatusBarPreferences(value.resolvedReadingStatusItems, defaults?.resolvedReadingStatusItems ?: cc.novelia.app.data.model.ReaderStatusItems.defaults) { onChange(value.copy(readingStatusItems = it)) }
                            }
                            TogglePreference("阅读时隐藏状态栏", "隐藏顶部时间、电量和 Wi-Fi 等图标；从顶部下滑可临时显示，退出阅读后恢复", value.hideStatusBar, defaultValue = defaults?.hideStatusBar) { onChange(value.copy(hideStatusBar = it)) }
                            TogglePreference("阅读进度条", "展开工具栏时显示，可快速跳转本章位置", value.showProgressBar, defaultValue = defaults?.showProgressBar) { onChange(value.copy(showProgressBar = it)) }
                            ReaderSlider("工具栏透明度 ${(value.resolvedToolbarTransparency * 100).roundToInt()}%", value.resolvedToolbarTransparency, 0f..1f,
                                modifier = Modifier.testTag("reader-toolbar-transparency"), defaultValue = defaults?.resolvedToolbarTransparency) { onChange(value.copy(toolbarTransparency = it)) }
                            Text("0% 为不透明，100% 为背景完全透明；文字和图标保持清晰。工具栏覆盖正文，显示或收起不会改变排版。",
                                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        var expanded by state.expandedGroup
                        ReaderPreferenceGroup("EPUB 附加显示", "假名注音、脚注弹窗与原书强调样式", expanded == "epub", { expanded = if (expanded == "epub") "" else "epub" }) {
                            TogglePreference("显示假名注音", "在汉字上方显示原书提供的读音", value.showEpubRuby, defaultValue = defaults?.showEpubRuby) { onChange(value.copy(showEpubRuby = it)) }
                            TogglePreference("点击脚注弹窗", "点击脚注引用查看说明；关闭后仍保留脚注文字", value.epubFootnotePopup, defaultValue = defaults?.epubFootnotePopup) { onChange(value.copy(epubFootnotePopup = it)) }
                            TogglePreference("显示原书强调样式", "保留加粗、斜体、下划线和删除线", value.showEpubStyles, defaultValue = defaults?.showEpubStyles) { onChange(value.copy(showEpubStyles = it)) }
                        }
                        if(onTextReplacements != null) Surface(onClick = onTextReplacements,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.FindReplace, null)
                                Column(Modifier.weight(1f)) {
                                    Text("本书文字替换 · ${value.textReplacements.size} 条", style = MaterialTheme.typography.titleSmall)
                                    Text("调整本书中的词句显示", style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                        }
                        ReaderPreferenceGroup("离线预读", "提前缓存后续章节与网络限制", expanded == "offline", { expanded = if(expanded == "offline") "" else "offline" }) {
                            ChoiceRow("自动预读后续章节", listOf("关闭", "1 章", "3 章", "5 章"), listOf(0, 1, 3, 5).indexOf(value.prefetchChapters).coerceAtLeast(0),
                                defaultSelected = defaults?.let { listOf(0, 1, 3, 5).indexOf(it.prefetchChapters) }) { onChange(value.copy(prefetchChapters = listOf(0, 1, 3, 5)[it])) }
                            if(value.prefetchChapters > 0) TogglePreference("仅 Wi-Fi 自动预读", "退出阅读或清理缓存时停止预读", value.prefetchWifiOnly, defaultValue = defaults?.prefetchWifiOnly) { onChange(value.copy(prefetchWifiOnly = it)) }
                        }
                        ReaderPreferenceGroup("进阶排版", "内容宽度、粗体、缩进与双语样式", expanded == "layout", { expanded = if(expanded == "layout") "" else "layout" }) {
                            ReaderSlider("内容宽度 ${value.width.toInt()} dp", value.width, 300f..900f, defaultValue = defaults?.width) { onChange(value.copy(width = it)) }
                            ReaderSlider("左右页边距 ${value.resolvedHorizontalMargin.toInt()} dp", value.resolvedHorizontalMargin, 0f..64f,
                                modifier = Modifier.testTag("reader-horizontal-margin"), defaultValue = defaults?.resolvedHorizontalMargin) { onChange(value.copy(horizontalMargin = it)) }
                            ReaderSlider("上下页边距 ${value.resolvedVerticalMargin.toInt()} dp", value.resolvedVerticalMargin, 0f..64f,
                                modifier = Modifier.testTag("reader-vertical-margin"), defaultValue = defaults?.resolvedVerticalMargin) { onChange(value.copy(verticalMargin = it)) }
                            ReaderSlider("辅文本不透明度 ${(value.secondaryAlpha * 100).toInt()}%", value.secondaryAlpha, .45f..1f, defaultValue = defaults?.secondaryAlpha) { onChange(value.copy(secondaryAlpha = it)) }
                            TogglePreference("加粗文字", "两种阅读模式使用一致的粗体", value.weight, defaultValue = defaults?.weight) { onChange(value.copy(weight = it)) }
                            TogglePreference("首行缩进", "统一为两个全角空格", value.indent, defaultValue = defaults?.indent) { onChange(value.copy(indent = it)) }
                            TogglePreference("辅文本下划线", "用于双语对照", value.underline, defaultValue = defaults?.underline) { onChange(value.copy(underline = it)) }
                        }
                        ReaderPreferenceGroup("朗读", "语速、语言、定时停止与连续听书", expanded == "speech", { expanded = if(expanded == "speech") "" else "speech" }) {
                            ReaderSlider("朗读速度 ${"%.1f".format(value.speechRate)}×", value.speechRate, .5f..2f, defaultValue = defaults?.speechRate) { onChange(value.copy(speechRate = it)) }
                            ChoiceRow("朗读语言", listOf("随显示模式", "中文", "日文"), listOf("auto", "zh", "jp").indexOf(value.speechLanguage),
                                defaultSelected = defaults?.let { listOf("auto", "zh", "jp").indexOf(it.speechLanguage) }) { onChange(value.copy(speechLanguage = listOf("auto", "zh", "jp")[it])) }
                            ChoiceRow("朗读定时停止", listOf("15 分钟", "30 分钟", "60 分钟"), listOf(15, 30, 60).indexOf(value.speechMinutes),
                                defaultSelected = defaults?.let { listOf(15, 30, 60).indexOf(it.speechMinutes) }) { onChange(value.copy(speechMinutes = listOf(15, 30, 60)[it])) }
                            TogglePreference("连续听书", "本章结束后自动进入下一章，定时停止不会重新计时", value.speechContinueChapters, defaultValue = defaults?.speechContinueChapters) { onChange(value.copy(speechContinueChapters = it)) }
                            if(value.speechContinueChapters) TogglePreference("允许联网续章", "优先读取本地和缓存；关闭后遇到未缓存章节暂停", value.speechNetworkContinuation, defaultValue = defaults?.speechNetworkContinuation) { onChange(value.copy(speechNetworkContinuation = it)) }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun ReaderPreferenceSection(title: String, summary: String, icon: ImageVector, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = appRoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable private fun ReaderPreferenceGroup(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val reduced = appReducedMotion()
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = appRoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { stateDescription = if(expanded) "已展开" else "已收起" }
                .motionClickable(onClick = onToggle).padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(expanded,
                enter = if(reduced) EnterTransition.None else expandVertically(tween(AppMotion.Standard)) + fadeIn(),
                exit = if(reduced) ExitTransition.None else shrinkVertically(tween(AppMotion.Standard)) + fadeOut()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}
