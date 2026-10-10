package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.FilterPanelExpandIcon
import cc.novelia.app.ui.components.FilterPanelVisibility

/** 开关始终留在筛选区顶部，只有下方条件参与展开动画。 */
@Composable internal fun ShelfControlsPanel(
    summary: String, expanded: Boolean, onToggle: () -> Unit, tagPrefix: String,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("$tagPrefix-controls")) {
        ShelfControlsSummary(summary, expanded, onToggle, tagPrefix, headerActions)
        FilterPanelVisibility(expanded) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 筛选摘要始终保留一行，完整条件仍可通过无障碍服务读取。 */
@Composable private fun ShelfControlsSummary(
    summary: String, expanded: Boolean, onToggle: () -> Unit, tagPrefix: String,
    headerActions: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix-controls-${if(expanded) "collapse" else "expand"}")
        .semantics { stateDescription = if(expanded) "已展开" else "已收起" }
        .clickable(role = Role.Button, onClickLabel = if(expanded) "收起书架筛选" else "展开书架筛选", onClick = onToggle)
        .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(summary, Modifier.weight(1f).testTag("$tagPrefix-controls-summary"), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        headerActions()
        Text(if(expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.testTag("$tagPrefix-controls-arrow")) { FilterPanelExpandIcon(expanded) }
    }
}

/** 数量与整理入口独立于筛选区，始终保留。 */
@Composable internal fun ShelfBatchHeader(
    label: String, managing: Boolean, onManage: () -> Unit, tagPrefix: String,
    manageEnabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("$tagPrefix-batch-header"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextButton(onClick = onManage, enabled = manageEnabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-batch-manage"),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if(managing) Icons.Outlined.Check else Icons.Outlined.Checklist, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if(managing) "完成" else "批量整理")
        }
    }
}
