@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.*

@Composable internal fun ShelfSelectionHeader(count: Int, enabled: Boolean, minimumHeight: Dp,
    onSelectAll: () -> Unit, onFinish: () -> Unit, tagPrefix: String = "local", selectAllLabel: String = "全选") {
    Row(Modifier.fillMaxWidth().heightIn(min = minimumHeight).padding(horizontal = 20.dp).testTag("$tagPrefix-selection-header"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("已选 $count 本", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        AppTextButton(onClick = onSelectAll, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-select-all")) { Text(selectAllLabel) }
        AppTextButton(onClick = onFinish, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-selection-done")) { Text("完成") }
    }
}

@Composable internal fun ShelfSelectionActions(count: Int, localFiles: Boolean, busy: Boolean, downloading: Boolean,
    canDownload: Boolean, onMove: () -> Unit, onStatus: () -> Unit, onDownload: () -> Unit,
    onRemove: () -> Unit, onDelete: () -> Unit, onSingleBook: (() -> Unit)?, comics: Boolean = false) {
    var more by remember { mutableStateOf(false) }
    val enabled = count > 0 && !busy
    LaunchedEffect(enabled) { if(!enabled) more = false }
    ShelfSelectionBar("local") {
        ShelfActionButton("移动", "移动到收藏夹", Icons.AutoMirrored.Outlined.DriveFileMove, "local-selection-move", enabled, onMove)
        ShelfActionButton("状态", "修改阅读状态", Icons.Outlined.BookmarkBorder, "local-selection-status", enabled, onStatus)
        if (!comics) ShelfActionButton(if(downloading) "加入中" else "下载", if(downloading) "正在加入下载" else "下载",
            Icons.Outlined.Download, "local-selection-download", enabled && !downloading && canDownload, onDownload)
        ShelfActionButton("更多", "更多操作", Icons.Outlined.MoreHoriz, "local-selection-more", enabled) { more = true }
    }
    if(more) AppSheet(onDismissRequest = { more = false }) {
        Column(Modifier.padding(bottom = 16.dp).testTag("local-selection-menu")) {
            Text("已选 $count 本", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            if(onSingleBook != null) MenuRow("管理这本书", "置顶、重命名或导出", Icons.Outlined.Settings, { more = false; onSingleBook() })
            Box(Modifier.testTag("local-selection-remove")) {
                MenuRow(if(localFiles) "删除本地副本" else "移出书架", "处理所选 $count 本，操作前再次确认", Icons.Outlined.RemoveCircleOutline,
                    { more = false; onRemove() })
            }
            Box(Modifier.testTag("local-selection-delete")) {
                MenuRow("彻底删除", "删除书籍及关联的本地资料", Icons.Outlined.DeleteForever, { more = false; onDelete() })
            }
        }
    }
}

@Composable internal fun ShelfSelectionBar(tagPrefix: String, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-selection-actions")) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable internal fun RowScope.ShelfActionButton(label: String, description: String, icon: ImageVector, tag: String,
    enabled: Boolean, onClick: () -> Unit) {
    AppTextButton(onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag(tag).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
