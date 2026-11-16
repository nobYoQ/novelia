package cc.novelia.app.ui.components.base

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cc.novelia.app.ui.theme.wallpaperCardColor

@Composable internal fun AppListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    containerColor: Color = wallpaperCardColor(ListItemDefaults.containerColor),
    contentColor: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = headlineContent, modifier = modifier, supportingContent = supportingContent,
        leadingContent = leadingContent, trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = containerColor, headlineColor = contentColor,
            supportingColor = contentColor, leadingIconColor = contentColor, trailingIconColor = contentColor),
    )
}
