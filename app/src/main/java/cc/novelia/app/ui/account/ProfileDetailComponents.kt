package cc.novelia.app.ui.account

import cc.novelia.app.ui.components.AppSwitch
import cc.novelia.app.ui.theme.LocalSquareCorners

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import cc.novelia.app.ui.theme.appRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.*
import cc.novelia.app.ui.theme.LocalEInkMode

/** “我的”二级页共用内容宽度，保留系统栏、返回和电子纸翻屏行为。 */
@Composable internal fun ProfileDetailScreen(
    title: String,
    back: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Screen(title, back, actions) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { content(PaddingValues(0.dp)) }
        }
    }
}

@Composable internal fun ProfileDetailList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: LazyListScope.() -> Unit,
) = AppLazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)

@Composable internal fun ProfileDetailCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    content: @Composable ColumnScope.() -> Unit,
) = Card(modifier.fillMaxWidth(), shape = appRoundedCornerShape(28.dp), colors = colors, border = profileCardBorder(), content = content)

@Composable internal fun ProfileMenuRow(
    title: String, description: String, icon: ImageVector, onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = appRoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
        Row(Modifier.heightIn(min = 80.dp).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if(description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(trailing != null) trailing()
            else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ProfileToggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ProfileDetailCard(Modifier.toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if(subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(LocalEInkMode.current && !LocalSquareCorners.current) Icon(if(value) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null,
                Modifier.size(48.dp), tint = if(value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            else AppSwitch(value, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable internal fun ProfileChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    ProfileDetailCard { ChoiceRow(label, options, selected, onSelect) }
}

@Composable internal fun ProfileSectionTitle(title: String, detail: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(detail != null) AppTextButton(onClick = onClick) { Text(detail) }
    }
}

@Composable internal fun ProfileSummary(title: String, description: String, icon: ImageVector? = null) {
    ProfileDetailCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(icon != null) Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            if(description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ProfileEmptyState(title: String, message: String, icon: ImageVector = Icons.Outlined.AutoStories,
    action: String? = null, onAction: () -> Unit = {},
) {
    ProfileDetailCard { EmptyState(title, message, icon, action, onAction) }
}
