@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.LocalReaderBrightnessPreview
import cc.novelia.app.ui.theme.nightReaderColors
import kotlinx.serialization.encodeToString
import kotlin.math.roundToInt

internal val LocalReaderPresetDraft = staticCompositionLocalOf { false }

/** 预设编辑有独立草稿；保存才写回，取消和关闭均还原临时亮度。 */
@Composable internal fun ReaderNightPresetEditor(settings: ReaderSettings, perBook: Boolean, initialTab: Int = 0,
    onDismiss: () -> Unit, onSave: (ReaderNightPreset, ReaderBrightness) -> Unit) {
    val draftSaver = remember {
        listSaver<ReaderSettings, String>(
            save = { listOf(appJson.encodeToString(it.nightPreset), appJson.encodeToString(it.nightBrightness)) },
            restore = { values -> settings.copy(nightReading = true,
                nightPreset = appJson.decodeFromString<ReaderNightPreset>(values[0]),
                nightBrightness = appJson.decodeFromString<ReaderBrightness>(values[1])) })
    }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(settings.copy(nightReading = true)) }
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var resetUndo by remember { mutableStateOf<ReaderSettings?>(null) }
    val previewBrightness = LocalReaderBrightnessPreview.current
    val latestPreview by rememberUpdatedState(previewBrightness)
    DisposableEffect(Unit) { onDispose { latestPreview(null) } }
    val shown = draft.readingAppearance()
    val changed = draft.nightPreset != settings.nightPreset || draft.nightBrightness != settings.nightBrightness
    val builtIn = draft.nightPreset == ReaderNightPreset() && draft.nightBrightness == ReaderBrightness.night()
    fun edit(value: ReaderSettings) { draft = draft.withReadingAppearance(value) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    AppDialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalReaderPresetDraft provides true) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth().height(maxHeight)
                .testTag("reader-night-preset-editor"), shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("夜间阅读预设", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        AppIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("reader-night-editor-close")) {
                            Icon(Icons.Outlined.Close, "取消编辑", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text("${if(perBook) "仅应用于本书" else "默认阅读设置"} · ${if(changed) "尚未保存" else if(builtIn) "内置预设" else "自定义预设"}",
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("reader-night-editor-scope"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if(settings.nightReading) "保存后用于当前夜间阅读，普通配置不变。" else "保存后用于夜间阅读，普通配置不变。",
                        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryTabRow(tab) {
                        listOf("亮度", "配色", "排版").forEachIndexed { index, label ->
                            Tab(tab == index, { tab = index }, text = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if(tab != 0) Box(Modifier.padding(vertical = 12.dp)) { ReaderNightPreview(shown) }
                    key(tab) {
                        AppScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(vertical = 12.dp)) {
                            when(tab) {
                                0 -> {
                                    EditorHint("调节时临时预览屏幕亮度，取消或关闭后恢复。数值在点击“保存预设”后保存。")
                                    ReaderBrightnessControls(draft.nightBrightness, onPreview = previewBrightness) { brightness ->
                                        draft = draft.copy(nightBrightness = brightness)
                                        previewBrightness(brightness)
                                    }
                                }
                                1 -> {
                                    EditorHeading("背景与文字")
                                    val palette = nightReaderColors(shown)
                                    val editable = if(shown.resolvedTheme == "custom") shown else shown.copy(theme = "custom", monochrome = false,
                                        customColors = ReaderCustomColors(palette.foreground.toArgb().toLong() and 0xFFFFFF,
                                            palette.background.toArgb().toLong() and 0xFFFFFF, palette.toolbar.toArgb().toLong() and 0xFFFFFF))
                                    ReaderThemeEditor(editable,
                                        draft.resetNightPreset().readingAppearance(), ::edit, showPreview = false, showReset = false,
                                        helperText = "点击颜色即可调整，也可输入色值。修改后保存为自定义夜间配色。")
                                    EditorHeading("辅助显示")
                                    ReaderSlider("辅文本清晰度 ${(shown.secondaryAlpha * 100).roundToInt()}%", shown.secondaryAlpha, .45f..1f,
                                        modifier = Modifier.testTag("night-secondary-alpha")) { edit(shown.copy(secondaryAlpha = it)) }
                                    EditorHint("数值越高，双语对照中的次要文字越清楚。")
                                    ReaderSlider("工具栏背景透明度 ${(shown.resolvedToolbarTransparency * 100).roundToInt()}%",
                                        shown.resolvedToolbarTransparency, 0f..1f, modifier = Modifier.testTag("night-toolbar-transparency")) {
                                        edit(shown.copy(toolbarTransparency = it))
                                    }
                                    EditorHint("0% 是实色背景，100% 是完全透明；文字和图标不受影响。")
                                }
                                else -> {
                                    EditorHeading("字体与字号")
                                    TogglePreference("沿用普通字体", "关闭后单独选择夜间字体", draft.nightPreset.fontFamily == null) {
                                        draft = draft.copy(nightPreset = draft.nightPreset.copy(fontFamily = if(it) null else shown.fontFamily,
                                            customFont = if(it) null else shown.customFont))
                                    }
                                    if(draft.nightPreset.fontFamily != null) ReaderFontPreference(shown, null, ::edit)
                                    TogglePreference("沿用普通字号", "当前普通字号为 ${draft.fontSize.toInt()}", draft.nightPreset.fontSize == null) {
                                        draft = draft.copy(nightPreset = draft.nightPreset.copy(fontSize = if(it) null else shown.fontSize))
                                    }
                                    if(draft.nightPreset.fontSize != null) ReaderSlider("夜间字号 ${shown.fontSize.toInt()}", shown.fontSize, ReaderSettings.FONT_SIZE_RANGE,
                                        modifier = Modifier.testTag("night-font-size"), eInkStep = 1f, eInkCoarseStep = 10f) { edit(shown.copy(fontSize = it)) }
                                    TogglePreference("加粗文字", "内置预设使用正常字重，保持较柔和的观感", shown.weight) { edit(shown.copy(weight = it)) }
                                    EditorHeading("行距与字距")
                                    TogglePreference("跟随普通行距", "在普通行距上增加 0.1 倍字号", draft.nightPreset.lineHeight == null) {
                                        draft = draft.copy(nightPreset = draft.nightPreset.copy(lineHeight = if(it) null else shown.lineHeight))
                                    }
                                    if(draft.nightPreset.lineHeight != null) ReaderSlider("夜间行距 ${"%.2f".format(shown.lineHeight)} 倍字号", shown.lineHeight,
                                        ReaderSettings.MIN_LINE_HEIGHT..4f, modifier = Modifier.testTag("night-line-height"),
                                        eInkStep = .05f, eInkCoarseStep = .5f) { edit(shown.copy(lineHeight = it)) }
                                    ReaderSlider("额外字距 ${(shown.letterSpacing * 100).roundToInt()}% 字号", shown.letterSpacing, 0f..0.15f,
                                        modifier = Modifier.testTag("night-letter-spacing"), eInkStep = .01f, eInkCoarseStep = .05f,
                                        valueFormatter = { "${(it * 100).roundToInt()}%" }) { edit(shown.copy(letterSpacing = it)) }
                                    EditorHint("0% 保持正常字距；稍微调高可以让文字更疏朗。")
                                    TogglePreference("沿用普通段距", "关闭后，单独调整夜间段落间的留白", draft.nightPreset.paragraphSpacing == null) {
                                        draft = draft.copy(nightPreset = draft.nightPreset.copy(paragraphSpacing = if(it) null else shown.resolvedParagraphSpacing))
                                    }
                                    if(draft.nightPreset.paragraphSpacing != null) ReaderSlider("段落间距 ${shown.resolvedParagraphSpacing.roundToInt()} dp · 额外留白",
                                        shown.resolvedParagraphSpacing, ReaderSettings.PARAGRAPH_SPACING_RANGE, modifier = Modifier.testTag("night-paragraph-spacing"),
                                        eInkStep = 1f, eInkCoarseStep = 10f) { edit(shown.copy(paragraphSpacing = it)) }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if(resetUndo == null) AppTextButton(onClick = {
                            resetUndo = draft
                            draft = draft.resetNightPreset()
                            previewBrightness(null)
                        }, modifier = Modifier.testTag("reader-reset-night-preset")) { Text("恢复内置预设") }
                        else AppTextButton(onClick = { draft = resetUndo!!; resetUndo = null; previewBrightness(null) },
                            modifier = Modifier.testTag("reader-undo-night-reset")) { Text("撤销恢复") }
                        Text(if(resetUndo == null) "重置亮度、配色和排版" else if(changed) "已恢复内置预设，保存后生效" else "当前已是内置预设，无需保存",
                            Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppOutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("reader-night-editor-cancel")) { Text("取消") }
                        AppButton(onClick = { previewBrightness(null); onSave(draft.nightPreset, draft.nightBrightness) }, enabled = changed,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reader-night-editor-save")) { Text("保存预设") }
                    }
                }
            }
        }
    }
}

@Composable private fun EditorHeading(text: String) {
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall)
}

@Composable private fun EditorHint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ReaderNightPreview(settings: ReaderSettings) {
    val colors = nightReaderColors(settings)
    val compact = LocalConfiguration.current.screenHeightDp < 650 || LocalDensity.current.fontScale > 1.15f
    var expanded by rememberSaveable(compact) { mutableStateOf(!compact) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        .heightIn(max = minOf(180.dp, LocalConfiguration.current.screenHeightDp.dp * .25f)).testTag("reader-night-preview"),
        color = colors.background, contentColor = colors.foreground, shape = MaterialTheme.shapes.medium) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = colors.toolbar.copy(alpha = 1f - settings.resolvedToolbarTransparency),
                contentColor = colors.foreground, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("夜间效果预览", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text(if(expanded) "收起" else "展开预览", style = MaterialTheme.typography.labelSmall)
                    Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if(expanded) Text(buildAnnotatedString {
                append("夜色渐深，故事仍在继续。\n")
                withStyle(SpanStyle(color = colors.foreground.copy(alpha = settings.secondaryAlpha))) { append("辅文本：把光线放柔。") }
            }, Modifier.padding(14.dp),
                fontFamily = rememberReaderFont(settings).family, fontSize = settings.fontSize.sp,
                fontWeight = if(settings.weight) FontWeight.Bold else FontWeight.Normal,
                lineHeight = settings.lineHeight.em,
                style = LocalTextStyle.current.copy(lineHeightStyle = ReaderLineHeightStyle), letterSpacing = settings.letterSpacing.em,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
