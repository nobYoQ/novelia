package cc.novelia.app.ui.reader

import android.graphics.Typeface
import android.content.Context
import cc.novelia.app.ui.components.documents.DocumentPickerFallback
import cc.novelia.app.ui.components.documents.DocumentPickerTypes
import cc.novelia.app.ui.components.documents.rememberOpenMultipleDocumentsPicker
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cc.novelia.app.data.model.*
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.reader.ReaderFontStore
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppDialog
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val READER_FONT_SAMPLE_ZH = "中文字体排版示例"
internal const val READER_FONT_SAMPLE_LATIN = "Aa Bb 0123456789"

internal data class ReaderFontFace(val typeface: Typeface, val available: Boolean = true, val loading: Boolean = false) {
    val family = FontFamily(typeface)
}

@Composable internal fun rememberReaderFont(settings: ReaderSettings, context: Context = LocalContext.current.applicationContext): ReaderFontFace {
    val revision by ReaderFontStore.revision.collectAsState()
    // 每种选择独立持有加载状态，切换后不会短暂使用上一种字体。
    return key(context, settings.resolvedFontFamily, settings.customFont, revision) {
        val initial = remember { ReaderFontStore.cached(context, settings)?.let { ReaderFontFace(it) } ?: ReaderFontFace(Typeface.DEFAULT, loading = true) }
        val font by produceState(initial, context) {
            if(initial.loading) {
                val loaded = withContext(Dispatchers.IO) { ReaderFontStore.load(context, settings) }
                value = ReaderFontFace(loaded ?: Typeface.DEFAULT, available = loaded != null)
            }
        }
        font
    }
}

@Composable internal fun ReaderFontPreference(settings: ReaderSettings, defaults: ReaderSettings?, onChange: (ReaderSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(onClick = { open = true }, shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh, border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("reader-font-settings")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = MaterialTheme.shapes.small) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text("Aa", fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("阅读字体", style = MaterialTheme.typography.titleSmall)
                Text(settings.fontLabel, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
        }
    }
    if(open) ReaderFontDialog(settings, defaults, onDismiss = { open = false }, onChange = onChange)
}

@Composable internal fun ReaderFontDialog(settings: ReaderSettings, defaults: ReaderSettings?, onDismiss: () -> Unit,
    onChange: (ReaderSettings) -> Unit) {
    val context = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
            // 字体库和预览异步更新时保持窗口高度，避免入场后重新居中造成跳动。
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(maxHeight).testTag("reader-font-dialog"),
                shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainer) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("阅读字体", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text(if(LocalReaderPresetDraft.current) "预设预览" else "即时生效", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                    AppScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ReaderFontOptions(context, settings, defaults, onChange)
                        Spacer(Modifier.height(2.dp))
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if(defaults != null && (settings.fontFamily != defaults.fontFamily || settings.customFont != defaults.customFont)) {
                            AppTextButton(onClick = { onChange(settings.copy(fontFamily = defaults.fontFamily, customFont = defaults.customFont)) },
                                modifier = Modifier.semantics { contentDescription = "恢复默认阅读字体" }) { Text("恢复默认") }
                        }
                        AppButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable private fun ReaderFontOptions(context: Context, settings: ReaderSettings, defaults: ReaderSettings?, onChange: (ReaderSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onChange)
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var importedFonts by remember { mutableStateOf(emptyList<ReaderCustomFont>()) }
    var loadingLibrary by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<ReaderCustomFont?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val revision by ReaderFontStore.revision.collectAsState()
    LaunchedEffect(context, revision, settings.customFont, defaults?.customFont) {
        try {
            val state = (context as? NoveliaApplication)?.store?.state?.value
            val known = listOfNotNull(settings.customFont, defaults?.customFont) +
                (listOfNotNull(state?.reader) + state?.bookSettings?.values.orEmpty()).flatMap { reader ->
                    reader.modeConfigurations().flatMap { listOfNotNull(it.customFont, it.nightPreset.customFont) }
                }
            importedFonts = ReaderFontStore.library(context, known)
        } catch(error: CancellationException) { throw error }
        catch(_: Exception) { feedback = "字体列表读取失败，请重新打开面板" }
        finally { loadingLibrary = false }
    }
    val font = rememberReaderFont(settings, context)
    val picker = rememberOpenMultipleDocumentsPicker(DocumentPickerTypes.Fonts) { uris ->
        if(uris.isNotEmpty()) scope.launch {
            importing = true
            feedback = null
            var succeeded = 0
            val failures = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    importProgress = "${index + 1}/${uris.size}"
                    try {
                        val imported = ReaderFontStore.importFont(context, uri)
                        change(current.copy(fontFamily = "custom", customFont = imported))
                        succeeded++
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failures += e.message ?: "字体导入失败，请重试" }
                }
                feedback = if(failures.isEmpty()) "已导入 $succeeded 款字体，已应用最后一款"
                    else "成功 $succeeded 款，失败 ${failures.size} 款。${failures.first()}"
            }
            finally { importing = false }
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("reader-font-preview-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字形预览", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(settings.fontLabel, Modifier.weight(1f).testTag("reader-font-name"), style = MaterialTheme.typography.labelMedium,
                    color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(READER_FONT_SAMPLE_ZH, Modifier.testTag("reader-font-preview"),
                fontFamily = font.family, fontSize = 20.sp, lineHeight = 30.sp,
                fontWeight = if(settings.weight) FontWeight.Bold else FontWeight.Normal, color = colors.onSurface)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(READER_FONT_SAMPLE_LATIN, fontFamily = font.family, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                Text("日本語の文字サンプル", fontFamily = font.family, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("内置字体", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
            Column(Modifier.selectableGroup()) {
                READER_FONT_FAMILIES.dropLast(1).forEachIndexed { index, family ->
                    ReaderFontOption(READER_FONT_LABELS[index], family, settings.resolvedFontFamily == family,
                        defaults?.resolvedFontFamily == family, ReaderFontStore.cached(context, ReaderSettings(fontFamily = family))?.let { FontFamily(it) } ?: FontFamily.Default) {
                        feedback = null
                        onChange(settings.copy(fontFamily = family))
                    }
                    if(index < 3) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.outlineVariant.copy(alpha = .6f))
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 缺失的当前字体仍保留入口和提示，方便重新导入或移除引用。
        val fonts = (importedFonts + listOfNotNull(settings.customFont?.takeIf { it.valid })).distinctBy { it.id }
        if(fonts.isNotEmpty()) {
            Text("已导入 · ${fonts.size}", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column(Modifier.selectableGroup()) {
                    fonts.forEachIndexed { index, imported ->
                        key(imported.id) {
                            val preview = rememberReaderFont(ReaderSettings(fontFamily = "custom", customFont = imported), context)
                            ReaderFontOption(imported.name, "custom-${imported.id}",
                                settings.resolvedFontFamily == "custom" && settings.customFont?.id == imported.id,
                                defaults?.resolvedFontFamily == "custom" && defaults.customFont?.id == imported.id,
                                preview.family, preview = preview, onDelete = {
                                    if(!importing && !deleting) { pendingDelete = imported; deleteError = null }
                                }) {
                                feedback = null
                                onChange(settings.copy(fontFamily = "custom", customFont = imported))
                            }
                            if(index < fonts.lastIndex) HorizontalDivider(Modifier.padding(start = 14.dp), color = colors.outlineVariant.copy(alpha = .6f))
                        }
                    }
                }
            }
        }
        if(loadingLibrary) Text("正在读取已导入字体…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if(!font.available) Text("字体在本机不可用，暂用系统字体。请重新导入。", color = colors.error, style = MaterialTheme.typography.bodySmall)
        AppOutlinedButton(onClick = { picker.launch() }, enabled = !importing && !deleting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader-font-import"), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Outlined.FileUpload, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if(importing) "正在导入 $importProgress…" else "导入字体文件")
        }
        DocumentPickerFallback(picker, enabled = !importing && !deleting)
        Text("支持多选 · TTF / OTF / TTC · 单个最大 64 MB\n已导入字体保留在本机，可随时切换；不随备份或同步传输。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    pendingDelete?.let { removing ->
        AppAlertDialog(onDismissRequest = { if(!deleting) pendingDelete = null }, title = { Text("删除字体？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将从本机删除“${removing.name}”。使用此字体的书籍将暂用系统字体，可重新导入恢复。")
                deleteError?.let { Text(it, color = colors.error) }
            } },
            confirmButton = { AppTextButton(enabled = !deleting, modifier = Modifier.testTag("reader-font-confirm-delete"), onClick = {
                scope.launch {
                    deleting = true
                    deleteError = null
                    try {
                        ReaderFontStore.remove(context, removing)
                        if(current.customFont?.id == removing.id) change(current.copy(
                            fontFamily = if(current.resolvedFontFamily == "custom") "system" else current.fontFamily, customFont = null))
                        importedFonts = importedFonts.filterNot { it.id == removing.id }
                        pendingDelete = null
                        feedback = "字体已删除"
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) { deleteError = error.message ?: "删除失败，请重试" }
                    finally { deleting = false }
                }
            }) { Text(if(deleting) "正在删除…" else "删除", color = colors.error) } },
            dismissButton = { AppTextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable private fun ReaderFontOption(label: String, family: String, isSelected: Boolean, isDefault: Boolean,
    previewFamily: FontFamily, preview: ReaderFontFace? = null, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if(isSelected) colors.primaryContainer.copy(alpha = .5f) else colors.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = if(preview == null) Alignment.CenterVertically else Alignment.Top) {
            Surface(onClick = onClick, color = Color.Transparent,
                modifier = Modifier.weight(1f).testTag("reader-font-option-$family").semantics { selected = isSelected; role = Role.RadioButton }) {
                if(preview != null) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if(isDefault) Text("默认", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Icon(if(isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                                Modifier.size(20.dp), tint = if(isSelected) colors.primary else colors.outline)
                        }
                        Box(Modifier.heightIn(min = 48.dp)) {
                            when {
                                preview.loading -> Text("正在加载字形…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                !preview.available -> Text("字体不可用，请重新导入", style = MaterialTheme.typography.bodySmall, color = colors.error)
                                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    // 统一字号和字重，选中状态不改变样张字形。
                                    Text(READER_FONT_SAMPLE_ZH, Modifier.testTag("reader-font-sample-$family"), fontFamily = preview.family,
                                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
                                    Text(READER_FONT_SAMPLE_LATIN, Modifier.testTag("reader-font-sample-latin-$family"), fontFamily = preview.family,
                                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Aa", Modifier.width(36.dp), fontFamily = previewFamily, fontSize = 22.sp,
                            color = if(isSelected) colors.primary else colors.onSurfaceVariant)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if(isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if(isDefault) Text("默认", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Icon(if(isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                            Modifier.size(20.dp), tint = if(isSelected) colors.primary else colors.outline)
                    }
                }
            }
            if(onDelete != null) AppIconButton(onClick = onDelete, modifier = Modifier.size(48.dp).testTag("reader-font-delete-$family")) {
                Icon(Icons.Outlined.DeleteOutline, "删除字体：$label", tint = colors.onSurfaceVariant)
            }
        }
    }
}
