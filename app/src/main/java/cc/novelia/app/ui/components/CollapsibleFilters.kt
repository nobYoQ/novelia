package cc.novelia.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun CollapsibleCloudFilters(expanded: Boolean, toggle: () -> Unit, summary: String, maxHeight: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, showHeader: Boolean = true, content: @Composable () -> Unit) {
    val scroll = rememberPanelScrollState(expanded)
    Surface(modifier, tonalElevation = 1.dp) {
        Column {
            if(showHeader) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = if (expanded) "收起筛选" else "展开筛选", onClick = toggle).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (expanded) "筛选云端收藏" else "展开筛选", style = MaterialTheme.typography.labelLarge)
                    Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                FilterPanelExpandIcon(expanded)
            }
            FilterPanelVisibility(expanded) {
                AppScrollColumn(Modifier.heightIn(max = maxHeight), state = scroll) { content() }
            }
        }
    }
}

@Composable internal fun rememberCloudFilterCollapse(enabled: Boolean, expanded: Boolean, collapse: () -> Unit): NestedScrollConnection {
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    val latestCollapse by rememberUpdatedState(collapse)
    return remember(enabled, expanded, threshold) {
        object : NestedScrollConnection {
            var downward = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (enabled && expanded && source == NestedScrollSource.UserInput) {
                    downward = if (available.y < 0) downward - available.y else 0f
                    if (downward >= threshold) { downward = 0f; latestCollapse() }
                }
                return Offset.Zero
            }
        }
    }
}
