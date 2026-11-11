@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.components.base.TextPrompt

@Composable internal fun CloudFavoriteLocalSheet(folders: List<String>, count: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    CloudFavoriteFolderSheet(folders, "加入本地收藏 · $count 本",
        "选择本地收藏夹。已在本地的作品保留原收藏夹，云端收藏不受影响。", onDismiss, onSave)
}

@Composable internal fun CloudFavoriteImportSheet(folders: List<String>, wenku: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val kind = if(wenku) "文库小说" else "网络小说"
    CloudFavoriteFolderSheet(folders, "导入全部$kind",
        "选择本地收藏夹，将所有云端收藏夹中的${kind}加入本地书架，不受当前分页、搜索和筛选影响。已有作品保留原收藏夹和阅读状态，仅加入书目，不下载正文。",
        onDismiss, onSave)
}

@Composable private fun CloudFavoriteFolderSheet(folders: List<String>, title: String, description: String,
    onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var create by remember { mutableStateOf(false) }
    AppSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Text(description, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            folders.forEach { folder -> MenuRow(folder, "保存到此设备", Icons.Outlined.Folder, { onSave(folder) }) }
            MenuRow("新建本地收藏夹", "创建并加入作品", Icons.Outlined.CreateNewFolder, { create = true })
        }
    }
    if(create) TextPrompt("新建本地收藏夹", "名称", onDismiss = { create = false }, onSave = onSave)
}
