package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import cc.novelia.app.data.model.FavoriteFolderOrder
import cc.novelia.app.data.model.FavoriteFolderSort
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.base.AppIconButton

@Composable internal fun FavoriteFolderMenu(
    folders: List<Folder>, selectedId: String?, onSelect: (String) -> Unit,
    fixedIds: Set<String>, order: FavoriteFolderOrder, originalLabel: String,
    onOrder: ((FavoriteFolderOrder) -> Unit)?, actions: @Composable () -> Unit,
) {
    var sorting by remember { mutableStateOf(false) }
    val movable = folders.filterNot { it.id in fixedIds }.map(Folder::id)
    val manual = sorting && order.sort == FavoriteFolderSort.Manual
    if(onOrder != null) {
        DropdownMenuItem(
            { Text("收藏夹排序") }, { sorting = !sorting },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null) },
            trailingIcon = { Icon(if(sorting) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
        )
        if(sorting) {
            val labels = listOf(originalLabel, "按名称", "手动排序")
            FavoriteFolderSort.entries.forEachIndexed { index, mode ->
                DropdownMenuItem({ Text(labels[index]) }, {
                    val ids = if(mode == FavoriteFolderSort.Manual && order.manualIds.isEmpty()) movable else order.manualIds
                    onOrder(FavoriteFolderOrder(mode, ids))
                }, modifier = Modifier.semantics { selected = mode == order.sort },
                    trailingIcon = if(mode == order.sort) ({ Icon(Icons.Outlined.Check, null) }) else null)
            }
        }
        HorizontalDivider()
    }
    folders.forEach { folder ->
        key(folder.id) {
            val rank = if(manual && onOrder != null) movable.indexOf(folder.id) else -1
            val trailing: (@Composable () -> Unit)? = when {
                rank >= 0 && onOrder != null -> ({
                    fun move(to: Int) {
                        val ids = movable.toMutableList().apply { add(to, removeAt(rank)) }
                        onOrder(order.copy(manualIds = ids))
                    }
                    Row {
                        AppIconButton({ move(rank - 1) }, enabled = rank > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${folder.title}")
                        }
                        AppIconButton({ move(rank + 1) }, enabled = rank < movable.lastIndex) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${folder.title}")
                        }
                    }
                })
                selectedId == folder.id -> ({ Icon(Icons.Outlined.Check, null) })
                else -> null
            }
            DropdownMenuItem(
                { Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                { onSelect(folder.id) }, trailingIcon = trailing,
            )
        }
    }
    if(folders.isNotEmpty()) HorizontalDivider()
    actions()
}
