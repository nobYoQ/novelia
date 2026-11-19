package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.FavoriteFolderOrder
import cc.novelia.app.data.model.FavoriteFolderSort
import cc.novelia.app.data.model.Folder
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppListItem
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.ChoiceRow

@Composable internal fun FavoriteFolderOrderDialog(
    folders: List<Folder>, defaultId: String, current: FavoriteFolderOrder, originalLabel: String,
    onDismiss: () -> Unit, onApply: (FavoriteFolderOrder) -> Unit,
) {
    var sort by rememberSaveable { mutableStateOf(current.sort) }
    var manualIds by rememberSaveable { mutableStateOf(current.manualIds) }
    val modes = FavoriteFolderSort.entries
    val movable = remember(folders, defaultId) { folders.filterNot { it.id == defaultId } }
    val ranked = remember(movable, defaultId, manualIds) { FavoriteFolderOrder(FavoriteFolderSort.Manual, manualIds).ordered(movable, defaultId) }
    fun move(from: Int, to: Int) {
        manualIds = ranked.map(Folder::id).toMutableList().apply { add(to, removeAt(from)) }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏夹排序") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ChoiceRow("排序方式", listOf(originalLabel, "按名称", "手动排序"), sort.ordinal) { index ->
                    val next = modes[index]
                    if(next == FavoriteFolderSort.Manual && manualIds.isEmpty()) {
                        manualIds = FavoriteFolderOrder(sort).ordered(movable, defaultId).map(Folder::id)
                    }
                    sort = next
                }
                if(sort == FavoriteFolderSort.Manual) {
                    if(ranked.isEmpty()) Text("暂无可调整顺序的收藏夹")
                    else AppLazyColumn(Modifier.heightIn(max = 276.dp), fillHeight = false,
                        listModifier = Modifier.testTag("favorite-folder-order-list")) {
                        itemsIndexed(ranked, key = { _, folder -> folder.id }) { index, folder ->
                            AppListItem(headlineContent = { Text(folder.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    Row {
                                        AppIconButton({ move(index, index - 1) }, enabled = index > 0) {
                                            Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${folder.title}")
                                        }
                                        AppIconButton({ move(index, index + 1) }, enabled = index < ranked.lastIndex) {
                                            Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${folder.title}")
                                        }
                                    }
                                })
                        }
                    }
                }
            }
        },
        dismissButton = { AppTextButton(onDismiss) { Text("取消") } },
        confirmButton = { AppTextButton({
            val ids = if(sort == FavoriteFolderSort.Manual) ranked.map(Folder::id) else manualIds
            onApply(FavoriteFolderOrder(sort, ids))
        }) { Text("应用排序") } },
    )
}
