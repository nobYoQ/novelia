package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.readingStatuses
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.CharacterCountFilterFields
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.CollapsibleCloudFilters

@Composable internal fun LocalShelfFilters(
    localFiles: Boolean, type: ShelfBookType, onType: (ShelfBookType) -> Unit,
    folders: List<String>, folder: String, onFolder: (String) -> Unit,
    sort: Int, onSort: (Int) -> Unit, query: String, onQuery: (String) -> Unit,
    status: String, onStatus: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    maxHeight: Dp, onCreateFolder: () -> Unit, onRenameFolder: () -> Unit, onDeleteFolder: () -> Unit,
    characters: CharacterCountFilter = CharacterCountFilter(), onCharacters: (CharacterCountFilter) -> Unit = {},
    controlsExpanded: Boolean = true, onToggleControls: () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val types = if(localFiles) listOf(ShelfBookType.All, ShelfBookType.Wenku, ShelfBookType.Local)
        else listOf(ShelfBookType.All, ShelfBookType.Web, ShelfBookType.Wenku, ShelfBookType.Local)
    val labels = if(localFiles) listOf("全部文件", "文库分卷", "本地小说") else listOf("全部", "网络小说", "文库小说", "本地小说")
    val choices = (listOf("全部") + folders).map { Folder(it, if(it == "全部") "全部收藏夹" else it) }
    val active = buildList {
        if(query.isNotBlank()) add("搜索：${query.trim()}")
        if(status != "全部") add(status)
        if(!localFiles && type == ShelfBookType.Web && characters.active) add(characters.summary())
    }
    val summary = active.joinToString(" · ")
    val context = listOf(labels[types.indexOf(type).coerceAtLeast(0)],
        choices.firstOrNull { it.id == folder }?.title ?: folder)
    ShelfControlsPanel((context + active + listOf("最近阅读", "添加时间", "书名排序")[sort.coerceIn(0, 2)])
        .joinToString(" · "), controlsExpanded, onToggleControls, "local") {
        ShelfKindSwitch(labels, types.indexOf(type).coerceAtLeast(0), "local-novel-kind") { onType(types[it]) }
        ShelfToolbar(choices, choices.firstOrNull { it.id == folder }, onFolder, sort.toString(), { onSort(it.toInt()) },
            listOf("0" to "最近阅读", "1" to "添加时间", "2" to "书名排序"), active.size, expanded,
            { onExpanded(!expanded); focus.clearFocus() }, "local") { close ->
            DropdownMenuItem({ Text("新建收藏夹") }, { close(); onCreateFolder() }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
            if(folder != "全部" && folder != "默认收藏") {
                DropdownMenuItem({ Text("重命名收藏夹") }, { close(); onRenameFolder() })
                DropdownMenuItem({ Text("删除收藏夹") }, { close(); onDeleteFolder() })
            }
        }
        if(active.isNotEmpty()) Text(summary, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            .testTag("local-filter-summary"), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        CollapsibleCloudFilters(expanded, { onExpanded(!expanded) }, summary, maxHeight, showHeader = false) {
            OutlinedTextField(query, onQuery, label = { Text("搜索书名或作者") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onExpanded(false) }),
                trailingIcon = { IconButton(onClick = { focus.clearFocus(); onExpanded(false) }) { Icon(Icons.Outlined.Search, "搜索本地书架") } },
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp))
            ChoiceRow("阅读状态", listOf("全部") + readingStatuses, (listOf("全部") + readingStatuses).indexOf(status)) { onStatus((listOf("全部") + readingStatuses)[it]) }
            if(!localFiles && type == ShelfBookType.Web) CharacterCountFilterFields(characters, onCharacters)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onQuery(""); onStatus("全部"); onCharacters(CharacterCountFilter()) }) { Text("重置筛选") }
                TextButton(onClick = { focus.clearFocus(); onExpanded(false) }) { Text("完成") }
            }
        }
        trailingContent()
    }
}
