@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlin.math.roundToInt

private val readerPanel = Color(0xFF1C211F)
private val readerTint = Color(0xFFB2DCC9)

@Composable internal fun ComicHeader(title: String, section: ComicSection, back: () -> Unit, details: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .88f), Color.Transparent)))
        .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(section.title, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppIconButton(onClick = details) { Icon(Icons.Outlined.Info, "书籍详情", tint = Color.White) }
    }
}

@Composable internal fun ComicPageIndicator(page: Int, count: Int, show: () -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).clickable(onClickLabel = "显示阅读工具", onClick = show),
        contentAlignment = Alignment.Center) {
        Text("$page / $count", Modifier.background(Color.Black.copy(alpha = .62f), appRoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
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
    Surface(modifier.widthIn(max = 620.dp).fillMaxWidth(), color = readerPanel.copy(alpha = .97f), contentColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasChapters) ChapterStep("上一话", previousChapter) else
                    Text("全册进度", style = MaterialTheme.typography.labelMedium, color = readerTint)
                Spacer(Modifier.weight(1f))
                Text(((pending ?: local) + 1).toString() + " / " + count, style = MaterialTheme.typography.labelLarge)
                if (hasChapters) {
                    Spacer(Modifier.weight(1f))
                    ChapterStep("下一话", nextChapter)
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton(enabled = canBack, onClick = { step(-1) }) {
                        Icon(if (strip) Icons.Outlined.ExpandLess else if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                            "上一$unit", tint = Color.White.copy(alpha = if (canBack) 1f else .32f))
                    }
                    Slider((pending ?: local).toFloat(), { pending = it.roundToInt() }, enabled = count > 1,
                        valueRange = 0f..maxOf(1, count - 1).toFloat(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = if (wholeBook) "全册页码" else "本话页码" },
                        colors = SliderDefaults.colors(thumbColor = readerTint, activeTrackColor = readerTint,
                            inactiveTrackColor = Color.White.copy(alpha = .25f)),
                        onValueChangeFinished = { pending?.let { seek(range.first + it) }; pending = null })
                    AppIconButton(enabled = canForward, onClick = { step(1) }) {
                        Icon(if (strip) Icons.Outlined.ExpandMore else if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
                            "下一$unit", tint = Color.White.copy(alpha = if (canForward) 1f else .32f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DockAction("目录", Icons.Outlined.FormatListBulleted, onChapters, Modifier.weight(1f))
                DockAction("缩略图", Icons.Outlined.GridView, onPages, Modifier.weight(1f), "页缩略图")
                Box(Modifier.weight(1f)) {
                    DockAction(mode.label, if (strip) Icons.Outlined.SwapVert else Icons.Outlined.SwapHoriz, { modes = true }, Modifier.fillMaxWidth())
                    DropdownMenu(expanded = modes, onDismissRequest = { modes = false }) {
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

@Composable private fun ChapterStep(label: String, jump: (() -> Unit)?) {
    TextButton(onClick = { jump?.invoke() }, enabled = jump != null, contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = readerTint, disabledContentColor = Color.White.copy(alpha = .32f))) {
        Text(label)
    }
}

@Composable private fun DockAction(label: String, icon: ImageVector, action: () -> Unit, modifier: Modifier, description: String? = null) {
    Column(modifier.clickable(role = Role.Button, onClick = action).heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, description, Modifier.size(21.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun ComicChapterSheet(sections: List<ComicSection>, current: Int, jump: (Int) -> Unit, close: () -> Unit) {
    AppSheet(onDismissRequest = close) {
        Text("章节目录", Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = rememberLazyListState(current), modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(sections.size, key = { sections[it].id }) { index ->
                val section = sections[index]
                ListItem(headlineContent = { Text(section.title) }, supportingContent = { Text(section.count.toString() + " 页") },
                    leadingContent = { if (index == current) Icon(Icons.Outlined.Bookmark, "当前章节", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = if (index == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier.clickable { jump(index) })
            }
        }
    }
}
