@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.reader.ReaderSeekSlider
import cc.novelia.app.ui.theme.ReaderColors
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder
import cc.novelia.app.ui.theme.monochromeDivider
import kotlin.math.roundToInt

@Composable internal fun ComicHeader(title: String, section: ComicSection, colors: ReaderColors,
    back: () -> Unit, details: () -> Unit, onCover: (() -> Unit)?) {
    val foreground = colors.foreground
    Surface(Modifier.fillMaxWidth().monochromeDivider(color = foreground).testTag("comic-top-toolbar"),
        color = colors.toolbar, contentColor = foreground) {
        TopAppBar(
            title = {
                Column {
                    Text(section.title.ifBlank { title }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (title.isNotBlank() && section.title.isNotBlank() && title != section.title) {
                        Text(title, style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = .8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = { AppIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = {
                if (onCover != null) AppIconButton(onClick = onCover) { Icon(Icons.Outlined.Image, "用本页设置封面") }
                AppIconButton(onClick = details) { Icon(Icons.Outlined.Info, "书籍详情") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                titleContentColor = foreground, navigationIconContentColor = foreground, actionIconContentColor = foreground),
        )
    }
}

@Composable internal fun ComicPageIndicator(page: Int, count: Int, colors: ReaderColors, show: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = show, shape = appRoundedCornerShape(12.dp), color = colors.toolbar,
            contentColor = colors.foreground, border = monochromeBorder(),
            modifier = Modifier.semantics { contentDescription = "第 $page 页，共 $count 页，显示阅读工具" }) {
            Text("$page / $count", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable internal fun ComicControls(range: IntRange, page: Int, mode: ComicReadingMode, hasChapters: Boolean, wholeBook: Boolean,
    previousChapter: (() -> Unit)?, nextChapter: (() -> Unit)?, canBack: Boolean,
    canForward: Boolean, step: (Int) -> Unit, seek: (Int) -> Unit, onChapters: () -> Unit, onPages: () -> Unit,
    onSettings: () -> Unit, onMode: (ComicReadingMode) -> Unit, colors: ReaderColors) {
    var pending by remember(range) { mutableStateOf<Int?>(null) }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction, range) {
        interaction.interactions.collect { if (it is DragInteraction.Cancel) pending = null }
    }
    val rtl = mode == ComicReadingMode.RightToLeft
    val strip = mode == ComicReadingMode.Webtoon
    val local = page - range.first
    val count = range.last - range.first + 1
    val unit = if (strip) "屏" else "页"
    val foreground = colors.foreground
    val buttons = IconButtonDefaults.iconButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f))
    Surface(Modifier.fillMaxWidth().monochromeDivider(atTop = true, color = foreground).testTag("comic-bottom-toolbar"),
        color = colors.toolbar, contentColor = foreground) {
        Box(contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (wholeBook) "全册进度" else "本话进度", style = MaterialTheme.typography.labelSmall)
                    Text("${(pending ?: local) + 1} / $count", Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                    ComicDirectionMenu(mode, foreground, onMode)
                }
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(enabled = canBack, onClick = { step(-1) }, colors = buttons) {
                            Icon(if (strip) Icons.Outlined.ExpandLess else if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft, "上一$unit")
                        }
                        ReaderSeekSlider((pending ?: local).toFloat(), { pending = it.roundToInt() },
                            onValueChangeFinished = { pending?.let { seek(range.first + it) }; pending = null },
                            foreground = foreground, enabled = count > 1, valueRange = 0f..maxOf(1, count - 1).toFloat(),
                            interactionSource = interaction, modifier = Modifier.weight(1f).semantics {
                                contentDescription = if (wholeBook) "全册页码" else "本话页码"
                            })
                        AppIconButton(enabled = canForward, onClick = { step(1) }, colors = buttons) {
                            Icon(if (strip) Icons.Outlined.ExpandMore else if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight, "下一$unit")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    if (hasChapters) AppIconButton(onClick = { previousChapter?.invoke() }, enabled = previousChapter != null, colors = buttons) {
                        Icon(Icons.Outlined.SkipPrevious, "上一话")
                    }
                    AppTextButton(onClick = onChapters, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) {
                        Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, null, Modifier.size(18.dp))
                        Text("目录", Modifier.padding(start = 6.dp))
                    }
                    AppIconButton(onClick = onPages, colors = buttons) { Icon(Icons.Outlined.GridView, "页缩略图") }
                    AppIconButton(onClick = onSettings, colors = buttons) { Icon(Icons.Outlined.Tune, "漫画显示设置") }
                    if (hasChapters) AppIconButton(onClick = { nextChapter?.invoke() }, enabled = nextChapter != null, colors = buttons) {
                        Icon(Icons.Outlined.SkipNext, "下一话")
                    }
                }
            }
        }
    }
}

@Composable private fun ComicDirectionMenu(mode: ComicReadingMode, foreground: Color, onMode: (ComicReadingMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppTextButton(onClick = { open = true }, colors = ButtonDefaults.textButtonColors(contentColor = foreground),
            modifier = Modifier.semantics { contentDescription = "切换阅读方向"; stateDescription = mode.label }) {
            Text(mode.label)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ComicReadingMode.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { open = false; onMode(choice) },
                    trailingIcon = { if (mode == choice) Icon(Icons.Outlined.Check, "当前模式") })
            }
        }
    }
}
