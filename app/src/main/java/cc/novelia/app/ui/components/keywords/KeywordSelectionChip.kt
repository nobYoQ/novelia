package cc.novelia.app.ui.components.keywords

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordSelectionState
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.isMonochromeTheme

/** 候选、已选条件和全部标签共用三态颜色，切换状态不改变标签的占位。 */
@Composable internal fun KeywordSelectionChip(
    label: String,
    state: KeywordSelectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val reducedMotion = appReducedMotion()
    val monochrome = isMonochromeTheme()
    val background = selectionColor(when(state) {
        KeywordSelectionState.Unselected -> scheme.surfaceContainerLow
        KeywordSelectionState.Included -> if(monochrome) scheme.primary else scheme.primaryContainer
        KeywordSelectionState.Excluded -> if(monochrome) scheme.surface else scheme.errorContainer
    }, reducedMotion, "keyword background")
    val foreground = selectionColor(when(state) {
        KeywordSelectionState.Unselected -> scheme.onSurfaceVariant
        KeywordSelectionState.Included -> if(monochrome) scheme.onPrimary else scheme.onPrimaryContainer
        KeywordSelectionState.Excluded -> scheme.onErrorContainer
    }, reducedMotion, "keyword foreground")
    val outline = selectionColor(when(state) {
        KeywordSelectionState.Unselected -> scheme.outlineVariant
        KeywordSelectionState.Included -> scheme.primary
        KeywordSelectionState.Excluded -> scheme.error
    }, reducedMotion, "keyword outline")
    AppSelectionChip(state != KeywordSelectionState.Unselected, onClick,
        label = { Text(label, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            textDecoration = if(state == KeywordSelectionState.Excluded) TextDecoration.LineThrough else null) },
        modifier = modifier.semantics {
            stateDescription = when(state) {
                KeywordSelectionState.Unselected -> "未选择"
                KeywordSelectionState.Included -> "已包含"
                KeywordSelectionState.Excluded -> "已排除"
            }
        },
        trailingIcon = trailingIcon, enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(containerColor = background, selectedContainerColor = background,
            labelColor = foreground, selectedLabelColor = foreground,
            iconColor = foreground, selectedLeadingIconColor = foreground, selectedTrailingIconColor = foreground),
        border = BorderStroke(if(monochrome) 1.dp else 1.5.dp, outline))
}

@Composable private fun selectionColor(target: Color, reducedMotion: Boolean, label: String): Color {
    val animated by animateColorAsState(target, tween(if(reducedMotion) 0 else AppMotion.Quick), label = label)
    return if(reducedMotion) target else animated
}
