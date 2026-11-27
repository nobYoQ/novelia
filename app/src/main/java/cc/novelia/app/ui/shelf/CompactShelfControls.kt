@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.theme.DefaultShapes
import cc.novelia.app.ui.theme.appControlShape
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.data.library.CUSTOM_SHELF_SORT
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.readingStatuses
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.data.model.FavoriteFolderOrder
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.base.*

/** 日常仅保留收藏夹/搜索/筛选，以及数量/排序；筛选草稿确认后才影响书架。 */
@Composable internal fun LocalShelfFilters(
    localFiles: Boolean, type: ShelfBookType, onType: (ShelfBookType) -> Unit,
    folders: List<Folder>, folder: String, onFolder: (String) -> Unit,
    sort: Int, onSort: (Int) -> Unit, query: String, onQuery: (String) -> Unit,
    status: String, onStatus: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    maxHeight: Dp, onCreateFolder: () -> Unit, onRenameFolder: () -> Unit, onDeleteFolder: () -> Unit,
    characters: CharacterCountFilter = CharacterCountFilter(), onCharacters: (CharacterCountFilter) -> Unit = {},
    countLabel: String = "", visible: Boolean = true, active: Boolean = true, modifier: Modifier = Modifier,
    grid: Boolean? = null, onToggleLayout: () -> Unit = {},
    folderOrder: FavoriteFolderOrder = FavoriteFolderOrder(), onOrderFolders: ((FavoriteFolderOrder) -> Unit)? = null,
    collapsed: Boolean = false,
    footer: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val types = if(localFiles) listOf(ShelfBookType.All, ShelfBookType.Wenku, ShelfBookType.Local, ShelfBookType.Series)
        else listOf(ShelfBookType.All, ShelfBookType.Web, ShelfBookType.Wenku, ShelfBookType.Local, ShelfBookType.Series)
    val labels = if(localFiles) listOf("全部文件", "文库分卷", "本地小说", "总集分卷") else listOf("全部", "网络小说", "文库小说", "本地小说", "本地总集")
    val statuses = listOf("全部") + readingStatuses
    val sorts = listOf("最近阅读", "添加时间", "书名排序", "自定义排序")
    val characterActive = !localFiles && type == ShelfBookType.Web && characters.active
    val count = (if(type != ShelfBookType.All) 1 else 0) + (if(status != "全部") 1 else 0) + (if(characterActive) 1 else 0)
    val showControls = visible && !collapsed
    LaunchedEffect(active, showControls) {
        if(!active || !showControls) { folderMenu = false; sortMenu = false; onExpanded(false); focus.clearFocus() }
    }
    ShelfControlsPanel(visible, collapsed, modifier, "local-controls", onCollapsed = { searchExpanded = false }, footer = footer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("local-toolbar"), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val folderMenuWidth = maxWidth
                AppTextButton(onClick = { folderMenu = true; focus.clearFocus() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("local-folder-picker"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if(folder == "全部") "全部收藏夹" else folder, Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    Icon(if(folderMenu) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                AppDropdownMenu(showControls && folderMenu, { folderMenu = false }, Modifier.width(folderMenuWidth).testTag("local-folder-menu")) {
                    FavoriteFolderMenu(listOf(Folder("", "全部收藏夹")) + folders, folders.firstOrNull { it.title == folder }?.id ?: "",
                        { id -> folderMenu = false; onFolder(folders.firstOrNull { it.id == id }?.title ?: "全部") },
                        setOf("", DEFAULT_FOLDER_ID), folderOrder, "按创建顺序", onOrderFolders) {
                        DropdownMenuItem({ Text("新建收藏夹") }, { folderMenu = false; onCreateFolder() }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                        if(folder != "全部" && folder != "默认收藏") {
                            DropdownMenuItem({ Text("重命名收藏夹") }, { folderMenu = false; onRenameFolder() })
                            DropdownMenuItem({ Text("删除收藏夹") }, { folderMenu = false; onDeleteFolder() })
                        }
                    }
                }
            }
            ShelfSearchToggle(searchExpanded, query.isNotBlank(), { searchExpanded = !searchExpanded; focus.clearFocus() }, "local")
            AppIconButton(onClick = { focus.clearFocus(); onExpanded(true) },
                modifier = Modifier.size(48.dp).testTag("local-filter-toggle").semantics {
                    stateDescription = if(count == 0) "未设置筛选条件" else "$count 项筛选条件"
                }) {
                BadgedBox(badge = { if(count > 0) Badge { Text(count.toString()) } }) { Icon(Icons.Outlined.Tune, "筛选书架") }
            }
        }
        ShelfSearchField(searchExpanded, query, onQuery, {}, { onQuery("") }, "搜索书名或作者", "local")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("local-results-header"), verticalAlignment = Alignment.CenterVertically) {
            Text(countLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                AppTextButton(onClick = { sortMenu = true; focus.clearFocus() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("local-sort-picker"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(sorts.getOrElse(sort) { sorts.first() }, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
                }
                AppDropdownMenu(showControls && sortMenu, { sortMenu = false }, Modifier.testTag("local-sort-menu")) {
                    sorts.forEachIndexed { index, label -> DropdownMenuItem({ Text(label) }, { sortMenu = false; onSort(index) },
                        trailingIcon = if(sort == index) ({ Icon(Icons.Outlined.Check, null) }) else null) }
                }
            }
            if(grid != null) ShelfLayoutToggle(grid, onToggleLayout, "local")
        }
        if(sort == CUSTOM_SHELF_SORT) Text("长按书籍后拖动调整顺序", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(count > 0 || (!searchExpanded && query.isNotBlank())) FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("local-filter-summary"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(type != ShelfBookType.All) ShelfFilterChip(labels[types.indexOf(type).coerceAtLeast(0)], "local-clear-type") { onType(ShelfBookType.All) }
            if(status != "全部") ShelfFilterChip(status, "local-clear-status") { onStatus("全部") }
            if(characterActive) ShelfFilterChip(characters.summary(), "local-clear-characters") { onCharacters(CharacterCountFilter()) }
            if(!searchExpanded && query.isNotBlank()) ShelfFilterChip("搜索：${query.trim()}", "local-clear-query") { onQuery("") }
        }
    }
    if(expanded && showControls && active) {
        var draftType by rememberSaveable { mutableStateOf(type) }
        var draftStatus by rememberSaveable { mutableStateOf(status) }
        var draftCharacters by rememberSaveable(stateSaver = CharacterCountFilterSaver) { mutableStateOf(characters) }
        var validCharacters by remember { mutableStateOf(true) }
        ShelfFilterSheet("筛选书架", "local", maxHeight, localFiles || draftType != ShelfBookType.Web || validCharacters,
            onDismiss = { onExpanded(false) },
            onReset = { draftType = ShelfBookType.All; draftStatus = "全部"; draftCharacters = CharacterCountFilter(); validCharacters = true },
            onApply = { focus.clearFocus(); onType(draftType); onStatus(draftStatus); onCharacters(draftCharacters); onExpanded(false) }) {
            FilterOptions("小说类型", labels, setOf(types.indexOf(draftType)), "local-novel-kind") { draftType = types[it] }
            FilterOptions("阅读状态", statuses, setOf(statuses.indexOf(draftStatus)), "local-reading-status") { draftStatus = statuses[it] }
            if(!localFiles && draftType == ShelfBookType.Web) {
                CharacterCountFilterFields(draftCharacters, onValidity = { validCharacters = it }) { draftCharacters = it }
                trailingContent()
            }
        }
    }
}

@Composable internal fun ShelfFilterChip(label: String, tag: String, onClear: () -> Unit) {
    InputChip(selected = true, onClick = onClear, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Outlined.Close, "清除$label", Modifier.size(16.dp)) }, shape = appControlShape(DefaultShapes.small),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
}
