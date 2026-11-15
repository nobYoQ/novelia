package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.ui.theme.*
import java.io.File
import kotlinx.coroutines.delay

@Composable internal fun ThemeEditorPreview(preset: ThemePreset, dark: Boolean, file: File?,
    onDarkChange: (Boolean) -> Unit, onBarClick: (ThemeBar) -> Unit) {
    val scheme = remember(preset.accent, preset.lightBackground, preset.darkBackground, dark) { customColorScheme(preset, dark) }
    // 拖动时色板和透明度逐步预览；短暂合并连续的模糊请求，避免为经过的每一档解码。
    val strengths = Triple(preset.blur, preset.topBar.blur, preset.bottomBar.blur)
    var shown by remember(preset.id) { mutableStateOf(strengths) }
    LaunchedEffect(strengths) { if (strengths != shown) { delay(80); shown = strengths } }
    val wallpaper = rememberThemeWallpaper(preset.copy(blur = shown.first,
        topBar = preset.topBar.copy(blur = shown.second), bottomBar = preset.bottomBar.copy(blur = shown.third)), file)

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("书架预览", style = MaterialTheme.typography.titleSmall)
                Text("点按顶栏或底栏可调整", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            listOf("浅色", "深色").forEachIndexed { i, text ->
                FilterChip(selected = dark == (i == 1), onClick = { onDarkChange(i == 1) }, label = { Text(text) })
                if (i == 0) Spacer(Modifier.width(6.dp))
            }
        }
        // 独立的预览视口与页面共用壁纸裁切和栏位绘制，预览配色可自由切换。
        CompositionLocalProvider(LocalThemePreset provides preset, LocalThemeWallpaper provides wallpaper,
            LocalEInkMode provides false, LocalAmoledBlack provides false) {
            MaterialTheme(colorScheme = scheme) {
                Surface(shape = MaterialTheme.shapes.large, color = scheme.background, contentColor = scheme.onBackground,
                    border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.testTag("theme-shelf-preview")) {
                    Box {
                        Spacer(Modifier.matchParentSize().themePaper(scheme.background, wallpaper, anchorBars = true))
                        Column {
                            PreviewBar(ThemeBar.Top, scheme.surface, onBarClick) {
                                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("书架", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                                        Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(18.dp))
                                    }
                                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        listOf("我的书架", "本地文件", "云端收藏").forEachIndexed { index, name ->
                                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(name, color = if (index == 0) scheme.primary else scheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (index == 0) Spacer(Modifier.padding(top = 2.dp).size(24.dp, 2.dp).background(scheme.primary))
                                            }
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                                Surface(shape = MaterialTheme.shapes.medium, color = wallpaperCardColor(scheme.surfaceContainerLow),
                                    contentColor = scheme.onSurface) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.AutoStories, null, Modifier.size(20.dp), tint = scheme.primary)
                                        Text("最近阅读", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            PreviewBar(ThemeBar.Bottom, NavigationBarDefaults.containerColor, onBarClick) {
                                listOf(Icons.Outlined.CollectionsBookmark, Icons.Outlined.Explore, Icons.Outlined.Forum, Icons.Outlined.PersonOutline)
                                    .forEachIndexed { index, icon ->
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            Surface(shape = MaterialTheme.shapes.extraLarge,
                                                color = if (index == 0) scheme.secondaryContainer else Color.Transparent) {
                                                Icon(icon, null, Modifier.padding(horizontal = 10.dp, vertical = 3.dp).size(18.dp),
                                                    tint = if (index == 0) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        val modeNote = when {
            LocalEInkMode.current -> "当前使用电子纸模式，关闭后可显示自定义外观。"
            LocalAmoledBlack.current -> "当前已开启 A 屏黑，深色模式将使用纯黑背景。"
            else -> null
        }
        modeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun PreviewBar(edge: ThemeBar, opaque: Color, select: (ThemeBar) -> Unit, content: @Composable RowScope.() -> Unit) {
    val bar = themeBarBackground(edge, opaque)
    val label = if (edge == ThemeBar.Top) "编辑顶栏毛玻璃" else "编辑底栏毛玻璃"
    Row(bar.modifier.fillMaxWidth().background(bar.color).clickable(role = Role.Button) { select(edge) }
        .semantics { contentDescription = label }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
