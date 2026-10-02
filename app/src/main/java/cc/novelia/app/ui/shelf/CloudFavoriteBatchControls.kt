@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.TextPrompt

@Composable internal fun CloudFavoriteBatchControls(
    managing: Boolean, selectedCount: Int, pageCount: Int, allOnPageSelected: Boolean,
    busy: Boolean, progress: Int, onManage: () -> Unit, onSelectPage: () -> Unit,
    onClear: () -> Unit, onLocal: () -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("cloud-batch-controls")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if(managing) "已选 $selectedCount 本" else "本页 $pageCount 本", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onManage, enabled = !busy && (managing || pageCount > 0),
                modifier = Modifier.heightIn(min = 48.dp).testTag("cloud-batch-manage")) {
                Icon(if(managing) Icons.Outlined.Check else Icons.Outlined.Checklist, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if(managing) "完成" else "批量整理")
            }
        }
        if(managing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectPage, enabled = !busy && pageCount > 0) { Text(if(allOnPageSelected) "取消本页全选" else "全选本页") }
                TextButton(onClick = onClear, enabled = !busy && selectedCount > 0) { Text("清空选择") }
                FilledTonalButton(onClick = onLocal, enabled = !busy && selectedCount > 0) { Text("加入本地收藏") }
                TextButton(onClick = onRemove, enabled = !busy && selectedCount > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("取消云端收藏") }
            }
            Text(if(busy) "正在处理 $progress / $selectedCount 本…" else "支持跨页选择；切换收藏夹或筛选会清空选择。",
                Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun CloudFavoriteLocalSheet(folders: List<String>, count: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var create by remember { mutableStateOf(false) }
    AppSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
            Text("加入本地收藏 · $count 本", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            Text("选择本地收藏夹。已在本地的作品保留原收藏夹，云端收藏不受影响。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            folders.forEach { folder -> MenuRow(folder, "保存到此设备", Icons.Outlined.Folder, { onSave(folder) }) }
            MenuRow("新建本地收藏夹", "创建并加入所选作品", Icons.Outlined.CreateNewFolder, { create = true })
        }
    }
    if(create) TextPrompt("新建本地收藏夹", "名称", onDismiss = { create = false }, onSave = onSave)
}
