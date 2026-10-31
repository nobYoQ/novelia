@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.book.WebSourceOptions

@Composable internal fun CompactCloudControls(
    folders: List<Folder>, current: Folder?, onFolder: (String) -> Unit, sort: String, onSort: (String) -> Unit,
    kind: Int, filter: CloudWebFilter, onApply: (Int, CloudWebFilter) -> Unit,
    query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, onClearQuery: () -> Unit,
    expanded: Boolean, onExpanded: (Boolean) -> Unit, countLabel: String, maxHeight: Dp,
    active: Boolean, visible: Boolean, modifier: Modifier = Modifier,
    grid: Boolean? = null, onToggleLayout: () -> Unit = {},
    folderActions: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var folderMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable(kind) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val sources = filter.source.split(',').filter(String::isNotBlank).toSet()
    val typeLabels = listOf("全部", "连载中", "已完结", "短篇")
    val levelLabels = listOf("全部", "一般向", "R18")
    val translateLabels = listOf("全部", "GPT", "Sakura")
    val count = if(kind == 0) listOf(sources.size != providers.size, filter.type != 0, filter.level != 0, filter.translate != 0).count { it } else 0
    LaunchedEffect(active, visible) { if(!active || !visible) { folderMenu = false; sortMenu = false; onExpanded(false); focus.clearFocus() } }
    if(visible) Column(modifier.fillMaxWidth().testTag("cloud-controls")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("cloud-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val folderMenuWidth = maxWidth
                AppTextButton({ folderMenu = true; focus.clearFocus() }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cloud-folder-picker"),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(current?.title ?: "收藏夹", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    Icon(if(folderMenu) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                AppDropdownMenu(folderMenu, { folderMenu = false }, Modifier.width(folderMenuWidth).testTag("cloud-folder-menu")) {
                    folders.forEach { item -> DropdownMenuItem({ Text(item.title) }, { folderMenu = false; onFolder(item.id) },
                        trailingIcon = if(current?.id == item.id) ({ Icon(Icons.Outlined.Check, null) }) else null) }
                    if(folders.isNotEmpty()) HorizontalDivider()
                    folderActions { folderMenu = false }
                }
            }
            if(kind == 0) ShelfSearchToggle(searchExpanded, filter.query.isNotBlank(), { searchExpanded = !searchExpanded; focus.clearFocus() }, "cloud")
            AppIconButton({ focus.clearFocus(); onExpanded(true) }, Modifier.size(48.dp).testTag("cloud-filter-toggle").semantics {
                stateDescription = if(count == 0) "未设置筛选条件" else "$count 项筛选条件"
            }) { BadgedBox(badge = { if(count > 0) Badge { Text(count.toString()) } }) { Icon(Icons.Outlined.Tune, "筛选云端收藏") } }
        }
        ShelfSearchField(searchExpanded && kind == 0, query, onQuery, onSubmit, onClearQuery, "搜索中 / 日标题或作者", "cloud")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("cloud-results-header"), verticalAlignment = Alignment.CenterVertically) {
            Text("${if(kind == 0) "网络小说" else "文库小说"} · $countLabel", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                AppTextButton({ sortMenu = true; focus.clearFocus() }, Modifier.heightIn(min = 48.dp).testTag("cloud-sort-picker"),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if(sort == "update") "更新时间" else "收藏时间", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
                }
                AppDropdownMenu(sortMenu, { sortMenu = false }, Modifier.testTag("cloud-sort-menu")) {
                    listOf("update" to "更新时间", "create" to "收藏时间").forEach { (id, label) ->
                        DropdownMenuItem({ Text(label) }, { sortMenu = false; onSort(id) },
                            trailingIcon = if(sort == id) ({ Icon(Icons.Outlined.Check, null) }) else null)
                    }
                }
            }
            if(grid != null) ShelfLayoutToggle(grid, onToggleLayout, "cloud")
        }
        if(kind == 0 && (count > 0 || (!searchExpanded && filter.query.isNotBlank()))) FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("cloud-filter-summary"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(sources.size != providers.size) ShelfFilterChip("${sources.size} 个书源", "cloud-clear-source") { onApply(kind, filter.copy(source = providers.keys.joinToString(","))) }
            if(filter.type != 0) ShelfFilterChip(typeLabels[filter.type], "cloud-clear-type") { onApply(kind, filter.copy(type = 0)) }
            if(filter.level != 0) ShelfFilterChip(levelLabels[filter.level], "cloud-clear-level") { onApply(kind, filter.copy(level = 0)) }
            if(filter.translate != 0) ShelfFilterChip(translateLabels[filter.translate], "cloud-clear-translate") { onApply(kind, filter.copy(translate = 0)) }
            if(!searchExpanded && filter.query.isNotBlank()) ShelfFilterChip("搜索：${filter.query}", "cloud-clear-query", onClearQuery)
        }
    }
    if(expanded && visible && active) {
        var draftKind by rememberSaveable { mutableIntStateOf(kind) }
        var draftSource by rememberSaveable { mutableStateOf(filter.source) }
        var draftType by rememberSaveable { mutableIntStateOf(filter.type) }
        var draftLevel by rememberSaveable { mutableIntStateOf(filter.level) }
        var draftTranslate by rememberSaveable { mutableIntStateOf(filter.translate) }
        ShelfFilterSheet("筛选云端收藏", "cloud", maxHeight,
            onDismiss = { onExpanded(false) },
            onReset = { draftKind = 0; draftSource = providers.keys.joinToString(","); draftType = 0; draftLevel = 0; draftTranslate = 0 },
            onApply = { focus.clearFocus(); onApply(draftKind, filter.copy(source = draftSource, type = draftType, level = draftLevel, translate = draftTranslate)); onExpanded(false) }) {
            ShelfFilterOptions("小说类型", listOf("网络小说", "文库小说"), setOf(draftKind), "cloud-novel-kind", 2) { draftKind = it }
            if(draftKind == 0) {
                val selectedSources = draftSource.split(',').filter(String::isNotBlank).toSet()
                WebSourceOptions(selectedSources, onChange = { selected ->
                    draftSource = providers.keys.filter { it in selected }.joinToString(",")
                }, modifier = Modifier.testTag("cloud-sources"), allowEmptySelection = true, tagPrefix = "cloud-source")
                ShelfFilterOptions("连载状态", typeLabels, setOf(draftType), "cloud-type") { draftType = it }
                ShelfFilterOptions("作品分级", levelLabels, setOf(draftLevel), "cloud-level", 3) { draftLevel = it }
                ShelfFilterOptions("翻译来源", translateLabels, setOf(draftTranslate), "cloud-translate", 3) { draftTranslate = it }
            }
        }
    }
}
