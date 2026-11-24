package cc.novelia.app.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppFilledTonalButton
import cc.novelia.app.ui.components.base.AppOutlinedButton

private data class BatchAction(val text: String, val icon: ImageVector, val tag: String, val enabled: Boolean, val run: () -> Unit)

@Composable internal fun DownloadBatchActions(
    completed: Int, selected: Int, enabled: Boolean, deleting: Boolean,
    onImport: () -> Unit, onExport: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    val file = if (completed > 1) " ZIP" else "文件"
    val actions = listOf(
        BatchAction("导入书架（$completed）", Icons.Outlined.LibraryAdd, "import", enabled && completed > 0, onImport),
        BatchAction("导出$file（$completed）", if (completed > 1) Icons.Outlined.FolderZip else Icons.Outlined.SaveAlt, "export", enabled && completed > 0, onExport),
        BatchAction("分享$file（$completed）", Icons.Outlined.Share, "share", enabled && completed > 0, onShare),
        BatchAction(if (deleting) "正在删除…" else "删除（$selected）", Icons.Outlined.DeleteOutline, "delete", enabled && selected > 0, onDelete),
    )
    val gap = 8.dp
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val maxLabel = actions.maxOf { measurer.measure(it.text, labelStyle, maxLines = 1).size.width }
        val cellWidth = with(LocalDensity.current) { maxLabel.toDp() } + 18.dp + 4.dp + 16.dp
        val columns = when {
            maxWidth >= cellWidth * 4 + gap * 3 -> 4
            maxWidth >= cellWidth * 2 + gap -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { action ->
                        val modifier = Modifier.weight(1f).testTag("download-batch-${action.tag}")
                        val content: @Composable RowScope.() -> Unit = {
                            Icon(action.icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(action.text)
                        }
                        if (action.tag == "import") AppFilledTonalButton(action.run, modifier, action.enabled, contentPadding = padding, content = content)
                        else AppOutlinedButton(action.run, modifier, action.enabled, contentPadding = padding, content = content)
                    }
                }
            }
        }
    }
}
