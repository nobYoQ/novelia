package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.AppDropdownMenu

/** 收藏夹管理和排序直接可达，无需先打开筛选面板。 */
@Composable internal fun CloudShelfToolbar(
    folders: List<Folder>, current: Folder?, onFolder: (String) -> Unit,
    sort: String, onSort: (String) -> Unit,
    filterCount: Int, expanded: Boolean, onToggleFilters: (() -> Unit)?,
    folderActions: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var folderMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val folderButton: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            TextButton(onClick = { folderMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("cloud-folder-picker"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(current?.title ?: "收藏夹", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            AppDropdownMenu(folderMenu, { folderMenu = false }) {
                folders.forEach { folder ->
                    DropdownMenuItem(text = { Text(folder.title) }, onClick = { folderMenu = false; onFolder(folder.id) },
                        trailingIcon = if(current?.id == folder.id) ({ Icon(Icons.Outlined.Check, null) }) else null)
                }
                if(folders.isNotEmpty()) HorizontalDivider()
                folderActions { folderMenu = false }
            }
        }
    }
    val sortingAndFilters: @Composable RowScope.(Boolean) -> Unit = { fill ->
        Box(if(fill) Modifier.weight(1f) else Modifier) {
            TextButton(onClick = { sortMenu = true }, modifier = (if(fill) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp)
                .testTag("cloud-sort-picker"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if(sort == "update") "更新时间" else "收藏时间", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
            }
            AppDropdownMenu(sortMenu, { sortMenu = false }) {
                listOf("update" to "更新时间", "create" to "收藏时间").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { sortMenu = false; onSort(value) },
                        trailingIcon = if(sort == value) ({ Icon(Icons.Outlined.Check, null) }) else null)
                }
            }
        }
        onToggleFilters?.let { toggle ->
            TextButton(onClick = toggle, modifier = (if(fill) Modifier.weight(1f) else Modifier).heightIn(min = 48.dp).testTag("cloud-filter-toggle")
                .semantics {
                    contentDescription = if(expanded) "收起筛选" else "展开筛选"
                    stateDescription = if(filterCount == 0) "未设置筛选条件" else "$filterCount 项筛选条件"
                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if(filterCount == 0) "筛选" else "筛选 $filterCount", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("cloud-toolbar")) {
        // 系统字号需要换行时，仍保留完整标签和触摸区域。
        if(maxWidth < (260 * LocalDensity.current.fontScale).dp) {
            Column {
                folderButton(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) { sortingAndFilters(true) }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            folderButton(Modifier.weight(1f))
            sortingAndFilters(false)
        }
    }
}
