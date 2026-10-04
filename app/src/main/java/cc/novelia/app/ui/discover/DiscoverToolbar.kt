package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.QuickFilter
import cc.novelia.app.ui.components.QuickFilterButton

/** 常用操作占一行；窄屏隐藏的快捷条件仍在完整筛选及摘要中可见。 */
@Composable internal fun DiscoverToolbar(
    filters: List<QuickFilter>,
    summary: List<String>,
    filterCount: Int,
    resultLabel: String,
    isSaved: Boolean,
    onFilter: () -> Unit,
    onAssistant: (() -> Unit)?,
    onRecent: () -> Unit,
    onSaved: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val visibleCount = if(maxWidth >= (300 * fontScale).dp) 2 else 1
        val compact = maxWidth < (330 * fontScale).dp
        val hiddenConditions = filters.drop(visibleCount).filter { it.selected != 0 }
            .map { it.options[it.selected] }
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discover-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                if(filters.isEmpty()) Text(resultLabel, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                else filters.take(visibleCount).forEach { filter ->
                    QuickFilterButton(filter, Modifier.weight(1f), compact = true)
                }
                if(compact) IconButton(onClick = onFilter) {
                    BadgedBox(badge = { if(filterCount > 0) Badge { Text(filterCount.toString()) } }) {
                        Icon(Icons.Outlined.Tune, "筛选${if(filterCount == 0) "" else " $filterCount"}")
                    }
                } else TextButton(onClick = onFilter, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if(filterCount == 0) "筛选" else "筛选 $filterCount", maxLines = 1)
                }
                if(onAssistant != null) {
                    if(compact) IconButton(onClick = onAssistant, Modifier.testTag("open-search-assistant")) {
                        Icon(Icons.AutoMirrored.Outlined.ManageSearch, "辅助搜索")
                    } else TextButton(onClick = onAssistant, modifier = Modifier.testTag("open-search-assistant")
                        .semantics { contentDescription = "辅助搜索" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("辅助搜索", maxLines = 1)
                    }
                }
                Box {
                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Outlined.MoreVert, "更多搜索操作") }
                    AppDropdownMenu(moreOpen, { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("最近搜索") }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                            onClick = { moreOpen = false; onRecent() })
                        DropdownMenuItem(text = { Text("保存的搜索") }, leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) },
                            onClick = { moreOpen = false; onSaved() })
                        DropdownMenuItem(text = { Text(if(isSaved) "搜索已保存" else "保存当前搜索") },
                            leadingIcon = { Icon(if(isSaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null) },
                            onClick = { moreOpen = false; onSave() })
                        if(filterCount > 0) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("清空筛选") }, leadingIcon = { Icon(Icons.Outlined.FilterAltOff, null) },
                                onClick = { moreOpen = false; onReset() })
                        }
                    }
                }
            }
            val conditions = hiddenConditions + summary
            if(conditions.isNotEmpty()) Text(conditions.joinToString(" · "),
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp).testTag("discover-filter-summary"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
