package cc.novelia.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.pressFeedback

private val ChipShape = RoundedCornerShape(14.dp)
internal val ChipSpacing = 12.dp

/** Give wrapped rows the same breathing room as their horizontal neighbours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AppChipFlowRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        verticalArrangement = Arrangement.spacedBy(ChipSpacing), content = content)
}

@Composable fun AppSelectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = ChipShape,
    colors: SelectableChipColors? = null,
    border: BorderStroke? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    FilterChip(selected = selected, onClick = onClick,
        label = { Box(Modifier.padding(vertical = 6.dp)) { label() } },
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressFeedback(interaction),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = shape,
        colors = colors ?: FilterChipDefaults.filterChipColors(containerColor = scheme.surfaceContainerLow,
            labelColor = scheme.onSurfaceVariant, selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer),
        border = border ?: BorderStroke(if(selected) 1.5.dp else 1.dp, if(selected) scheme.primary else scheme.outlineVariant),
        interactionSource = interaction)
}

@Composable fun AppActionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    AssistChip(onClick = onClick, label = { Box(Modifier.padding(vertical = 6.dp)) { label() } },
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pressFeedback(interaction),
        leadingIcon = leadingIcon, shape = ChipShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = colors.surfaceContainerLow, labelColor = colors.onSurfaceVariant),
        border = BorderStroke(1.dp, colors.outlineVariant), interactionSource = interaction)
}
