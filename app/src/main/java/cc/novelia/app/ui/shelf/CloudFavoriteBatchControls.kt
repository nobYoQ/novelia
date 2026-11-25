@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.components.base.TextPrompt

@Composable internal fun CloudSelectionDialog(
    selection: Map<String, BookCard>, onUnselect: (String) -> Unit, onDismiss: () -> Unit,
    onConfirmRemoval: (() -> Unit)? = null,
) {
    val removing = onConfirmRemoval != null
    var expanded by rememberSaveable { mutableStateOf(!removing) }
    AppAlertDialog(onDismissRequest = onDismiss,
        title = { Text(if(removing) "取消 ${selection.size} 本云端收藏？" else "已选 ${selection.size} 本") },
        text = {
            Column {
                if(removing) {
                    Text("仅取消所选作品的云端收藏，本地收藏和阅读记录会保留。")
                    AppTextButton(onClick = { expanded = !expanded }) {
                        Text(if(expanded) "收起所选书目" else "查看所选书目")
                    }
                }
                if(expanded && selection.isEmpty()) Text("尚未选择书籍")
                if(expanded && selection.isNotEmpty()) AppLazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), fillHeight = false,
                ) {
                    items(selection.values.toList(), key = { it.ref.key }) { book ->
                        val source = if(book.ref.isWenku) "文库" else providers[book.ref.provider] ?: book.ref.provider
                        AppListItem(headlineContent = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(source,
                                book.authors.joinToString("、").takeIf(String::isNotBlank)).joinToString(" · ")) },
                            trailingContent = { AppIconButton(onClick = { onUnselect(book.ref.key) }) {
                                Icon(Icons.Outlined.Close, "取消选择《${book.title}》")
                            } })
                    }
                }
            }
        },
        confirmButton = {
            if(onConfirmRemoval == null) AppTextButton(onDismiss) { Text("完成") }
            else AppTextButton(onConfirmRemoval, enabled = selection.isNotEmpty()) { Text("取消云端收藏") }
        },
        dismissButton = { if(removing) AppTextButton(onDismiss) { Text("取消") } })
}

@Composable internal fun CloudFavoriteLocalSheet(folders: List<String>, count: Int, onDismiss: () -> Unit,
    onCloudFolders: (() -> Unit)? = null, onSave: (String) -> Unit) {
    CloudFavoriteFolderSheet(folders, "加入本地收藏 · $count 本",
        "选择单一本地收藏夹，或按云端收藏夹归类。云端收藏和阅读记录不受影响。", onDismiss, onCloudFolders, onSave)
}

@Composable internal fun CloudFavoriteImportSheet(folders: List<String>, wenku: Boolean, onDismiss: () -> Unit,
    onCloudFolders: (() -> Unit)? = null, onSave: (String) -> Unit) {
    val kind = if(wenku) "文库小说" else "网络小说"
    CloudFavoriteFolderSheet(folders, "导入全部$kind",
        "将所有云端收藏夹中的${kind}加入本地书架，不受当前分页、搜索和筛选影响。可选择单一本地收藏夹，或按云端收藏夹归类。仅加入书目，不下载正文。",
        onDismiss, onCloudFolders, onSave)
}

@Composable private fun CloudFavoriteFolderSheet(folders: List<String>, title: String, description: String,
    onDismiss: () -> Unit, onCloudFolders: (() -> Unit)?, onSave: (String) -> Unit) {
    var create by remember { mutableStateOf(false) }
    AppSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Text(description, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            if(onCloudFolders != null) Box(Modifier.testTag("cloud-import-by-folders")) {
                MenuRow("按云端收藏夹导入", "加入同名本地收藏夹，不存在时自动创建；已有书籍也调整分组，保留阅读资料。",
                    Icons.Outlined.FolderCopy, onCloudFolders)
            }
            Text("加入单一收藏夹", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            Text("已在本地的作品保留原收藏夹。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            folders.forEach { folder -> MenuRow(folder, "保存到此设备", Icons.Outlined.Folder, { onSave(folder) }) }
            MenuRow("新建本地收藏夹", "创建并加入作品", Icons.Outlined.CreateNewFolder, { create = true })
        }
    }
    if(create) TextPrompt("新建本地收藏夹", "名称", onDismiss = { create = false }, onSave = onSave)
}
