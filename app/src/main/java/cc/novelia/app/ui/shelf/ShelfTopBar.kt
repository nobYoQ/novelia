@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookMedium
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.theme.appShape

@Composable internal fun ShelfTopBar(
    medium: BookMedium, switchEnabled: Boolean, onSwitch: () -> Unit,
    importEnabled: Boolean, onImport: () -> Unit, onHistory: () -> Unit,
    onUpdates: () -> Unit, onCheckUpdates: () -> Unit, rotation: () -> Float = { 0f },
) {
    val novel = medium == BookMedium.Novel
    val label = if (novel) "小说" else "漫画"
    val switchLabel = if (novel) "切换到漫画书架" else "切换到小说书架"
    BoxWithConstraints {
        // 分栏或大字体下，为标题和切换按钮留足宽度。
        val compact = maxWidth < 250.dp + (80 * LocalDensity.current.fontScale).dp
        TopAppBar(title = {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("书架", maxLines = 1)
                val colors = MaterialTheme.colorScheme
                Surface(onClick = onSwitch, enabled = switchEnabled,
                    color = lerp(colors.surface, colors.primary, if (switchEnabled) .19f else .075f),
                    contentColor = colors.onSurface.copy(alpha = if (switchEnabled) 1f else .45f),
                    shape = appShape(MaterialTheme.shapes.small),
                    modifier = Modifier.testTag("shelf-medium").semantics {
                        stateDescription = "当前为${label}书架"
                        onClick(label = switchLabel, action = null)
                    }) {
                    Row(Modifier.heightIn(min = 30.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Autorenew, null, Modifier.size(17.dp).graphicsLayer { rotationZ = rotation() })
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }, actions = {
            if (novel && compact) {
                var more by remember { mutableStateOf(false) }
                Box {
                    AppIconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "书架操作") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("查看书架更新") }, onClick = { more = false; onUpdates() })
                        DropdownMenuItem(text = { Text("检查更新") }, onClick = { more = false; onCheckUpdates() })
                        DropdownMenuItem(text = { Text("阅读历史") }, onClick = { more = false; onHistory() })
                    }
                }
            } else {
                if (novel) {
                    AppIconButton(onClick = onUpdates) { Icon(Icons.Outlined.NewReleases, "查看书架更新") }
                    AppIconButton(onClick = onCheckUpdates) { Icon(Icons.Outlined.Sync, "检查更新") }
                }
                AppIconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "阅读历史") }
            }
            AppIconButton(onClick = onImport, enabled = importEnabled) { Icon(Icons.Outlined.FileOpen, "导入本地文件") }
        })
    }
}
