@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.launch

// 静态面板已由 AppSheet 限高，内容填满其剩余空间，避免再次按比例缩小。
@Composable internal fun Modifier.comicPanelHeight(): Modifier = fillMaxHeight(if (appReducedMotion()) 1f else .82f)

/** 漫画面板共用标题层级和关闭入口。 */
@Composable internal fun ComicSheetHeader(title: String, subtitle: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭$title") }
    }
}

@Composable internal fun ComicChapterSheet(sections: List<ComicSection>, current: Int, jump: (Int) -> Unit, close: () -> Unit) {
    AppSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().comicPanelHeight().testTag("comic-chapter-panel")) {
            ComicSheetHeader("章节目录", "${sections.size} 话 · ${sections.sumOf { it.count }} 页", close)
            val scroll = rememberLazyListState(current)
            val scope = rememberCoroutineScope()
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("正在阅读 · ${sections[current].title}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                AppTextButton(onClick = { scope.launch { scroll.scrollToItem(current) } }) { Text("定位当前") }
            }
            HorizontalDivider()
            AppLazyColumn(state = scroll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sections.size, key = { sections[it].id }) { index ->
                    val section = sections[index]
                    val active = index == current
                    Surface(onClick = { jump(index) }, modifier = Modifier.fillMaxWidth().semantics { selected = active },
                        shape = MaterialTheme.shapes.large,
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .4f)) else null) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(shape = MaterialTheme.shapes.medium,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
                                Box(Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(section.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${section.count} 页${if (active) " · 正在阅读" else ""}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (active) Icon(Icons.Outlined.Bookmark, "当前章节", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
