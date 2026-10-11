package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppBadge
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumRules
import cc.novelia.app.data.community.ForumTag
import cc.novelia.app.ui.components.base.appHorizontalScroll
import cc.novelia.app.ui.theme.LocalEInkMode

@Composable internal fun ForumTagFilterToggle(expanded: Boolean, selectedTag: String?, onClick: () -> Unit) {
    AppIconButton(onClick, Modifier.testTag("forum-tag-toggle").semantics {
        stateDescription = selectedTag?.let { "已筛选：$it" } ?: "全部标签"
    }) {
        BadgedBox(badge = { if(selectedTag != null) AppBadge() }) {
            Icon(Icons.Outlined.FilterAlt, if(expanded) "收起标签筛选" else "展开标签筛选",
                tint = if(expanded || selectedTag != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    }
}

@Composable internal fun ForumTagBadges(tags: List<ForumTag>, modifier: Modifier = Modifier) {
    if(tags.isEmpty()) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.distinctBy { it.id }.forEach { tag ->
            val (background, foreground) = forumTagColors(tag.color)
            Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.extraSmall,
                border = if(LocalEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null) {
                Text(tag.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable internal fun ForumTagSelector(tags: List<ForumTag>, selectedIds: List<Long>, enabled: Boolean,
    onSelect: (List<Long>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("标签 ${selectedIds.size} / ${ForumRules.TAG_LIMIT}", style = MaterialTheme.typography.labelMedium)
        if(tags.isEmpty()) Text("这个分类暂时没有可用标签。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else FlowRow(Modifier.fillMaxWidth().testTag("forum-tag-selector"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.sortedBy { it.sortOrder }.distinctBy { it.id }.forEach { tag ->
                val selected = tag.id in selectedIds
                FilterChip(selected = selected, enabled = enabled && (selected || selectedIds.size < ForumRules.TAG_LIMIT),
                    onClick = { onSelect(if(selected) selectedIds - tag.id else selectedIds + tag.id) },
                    leadingIcon = if(selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    label = { Text(tag.name) }, colors = forumTagChipColors(tag.color))
            }
        }
    }
}

@Composable internal fun ForumTagFilter(tags: List<ForumTag>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    if(tags.isEmpty()) return
    Row(Modifier.fillMaxWidth().testTag("forum-tag-filter").appHorizontalScroll(rememberScrollState())
        .padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text("全部标签") },
            leadingIcon = if(selectedId == null) ({ Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null)
        tags.sortedBy { it.sortOrder }.distinctBy { it.id }.forEach { tag ->
            FilterChip(selected = selectedId == tag.id, onClick = { onSelect(if(selectedId == tag.id) null else tag.id) },
                leadingIcon = if(selectedId == tag.id) ({ Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                label = { Text(tag.name) }, colors = forumTagChipColors(tag.color))
        }
    }
}

@Composable private fun forumTagChipColors(color: Int): SelectableChipColors {
    val (background, foreground) = forumTagColors(color)
    return FilterChipDefaults.filterChipColors(selectedContainerColor = background,
        selectedLabelColor = foreground, selectedLeadingIconColor = foreground)
}

/** 与网页的五色索引一致；深色主题提高文字亮度，电子纸使用带边框的黑白标签。 */
@Composable private fun forumTagColors(color: Int): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    if(LocalEInkMode.current) return scheme.surface to scheme.onSurface
    val dark = scheme.surface.luminance() < .5f
    val foreground = when((kotlin.math.abs(color.toLong()) % 5).toInt()) {
        1 -> Color(if(dark) 0xFF93C5FD else 0xFF2563EB)
        2 -> Color(if(dark) 0xFFFDBA74 else 0xFFEA580C)
        3 -> Color(if(dark) 0xFFD8B4FE else 0xFF9333EA)
        4 -> Color(if(dark) 0xFFFCA5A5 else 0xFFDC2626)
        else -> return scheme.primaryContainer to scheme.onPrimaryContainer
    }
    return foreground.copy(alpha = .12f) to foreground
}
