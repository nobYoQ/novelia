@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlin.math.roundToInt

@Composable internal fun ComicHeader(title: String, section: ComicSection, back: () -> Unit, details: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().testTag("comic-top-toolbar"), color = MaterialTheme.colorScheme.surfaceContainer) {
        TopAppBar(
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(section.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { AppIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { AppIconButton(onClick = details) { Icon(Icons.Outlined.Info, "书籍详情") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

@Composable internal fun ComicPageIndicator(page: Int, count: Int, show: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 64.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = show, shape = appRoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .9f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.semantics { contentDescription = "第 $page 页，共 $count 页，显示阅读工具" }) {
            Text("$page / $count", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable internal fun ComicControls(range: IntRange, page: Int, mode: ComicReadingMode, hasChapters: Boolean, wholeBook: Boolean,
    previousChapter: (() -> Unit)?, nextChapter: (() -> Unit)?, canBack: Boolean,
    canForward: Boolean, step: (Int) -> Unit, seek: (Int) -> Unit, onChapters: () -> Unit, onPages: () -> Unit,
    onSettings: () -> Unit, onMode: (ComicReadingMode) -> Unit, modifier: Modifier) {
    var pending by remember(range) { mutableStateOf<Int?>(null) }
    var modes by remember { mutableStateOf(false) }
    val rtl = mode == ComicReadingMode.RightToLeft
    val strip = mode == ComicReadingMode.Webtoon
    val local = page - range.first
    val count = range.last - range.first + 1
    val unit = if (strip) "屏" else "页"
    Surface(modifier.widthIn(max = 640.dp).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().testTag("comic-bottom-toolbar"),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = appRoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (wholeBook) "全册进度" else "本话进度", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(pending ?: local) + 1} / $count", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton(enabled = canBack, onClick = { step(-1) }) {
                        Icon(if (strip) Icons.Outlined.ExpandLess else if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft, "上一$unit")
                    }
                    AppSlider((pending ?: local).toFloat(), { pending = it.roundToInt() }, enabled = count > 1,
                        valueRange = 0f..maxOf(1, count - 1).toFloat(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = if (wholeBook) "全册页码" else "本话页码" },
                        onValueChangeFinished = { pending?.let { seek(range.first + it) }; pending = null })
                    AppIconButton(enabled = canForward, onClick = { step(1) }) {
                        Icon(if (strip) Icons.Outlined.ExpandMore else if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight, "下一$unit")
                    }
                }
            }
            if (hasChapters) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                AppTextButton(onClick = { previousChapter?.invoke() }, enabled = previousChapter != null) {
                    Icon(Icons.Outlined.SkipPrevious, null, Modifier.size(18.dp))
                    Text("上一话", Modifier.padding(start = 4.dp))
                }
                AppTextButton(onClick = { nextChapter?.invoke() }, enabled = nextChapter != null) {
                    Text("下一话", Modifier.padding(end = 4.dp))
                    Icon(Icons.Outlined.SkipNext, null, Modifier.size(18.dp))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DockAction("目录", Icons.AutoMirrored.Outlined.FormatListBulleted, onChapters, Modifier.weight(1f))
                DockAction("缩略图", Icons.Outlined.GridView, onPages, Modifier.weight(1f), "页缩略图")
                Box(Modifier.weight(1f)) {
                    DockAction(mode.label, if (strip) Icons.Outlined.SwapVert else Icons.Outlined.SwapHoriz,
                        { modes = true }, Modifier.fillMaxWidth(), "切换阅读方向")
                    AppDropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
                        ComicReadingMode.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = { modes = false; onMode(choice) },
                                trailingIcon = { if (mode == choice) Icon(Icons.Outlined.Check, "当前模式") })
                        }
                    }
                }
                DockAction("设置", Icons.Outlined.Tune, onSettings, Modifier.weight(1f), "漫画显示设置")
            }
        }
    }
}

@Composable private fun DockAction(label: String, icon: ImageVector, action: () -> Unit, modifier: Modifier, description: String? = null) {
    Surface(onClick = action, modifier = modifier, shape = MaterialTheme.shapes.medium, color = Color.Transparent) {
        Column(Modifier.heightIn(min = 64.dp).padding(horizontal = 2.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Icon(icon, description, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
