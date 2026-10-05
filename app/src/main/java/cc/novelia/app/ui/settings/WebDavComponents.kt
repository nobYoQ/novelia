package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.motionClickable

/** 两个同步页面共用的层次、留白和操作区，颜色跟随应用主题。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavPage(
    title: String,
    onBack: () -> Unit,
    action: String,
    actionIcon: ImageVector,
    actionEnabled: Boolean,
    working: Boolean,
    onAction: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        ) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onAction, enabled = actionEnabled,
                        shape = CircleShape,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        if(working && !LocalEInkMode.current) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(actionIcon, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(action, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        content = content,
    )
}

@Composable
internal fun WebDavHero(title: String, description: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(24.dp),
            border = if(LocalEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(36.dp)) }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun webDavRowShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if(index == 0) 28.dp else 6.dp,
    topEnd = if(index == 0) 28.dp else 6.dp,
    bottomStart = if(index == count - 1) 28.dp else 6.dp,
    bottomEnd = if(index == count - 1) 28.dp else 6.dp,
)

@Composable
internal fun WebDavCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if(LocalEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        content = content,
    )
}

@Composable
internal fun WebDavRow(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    trailing: @Composable () -> Unit,
) {
    WebDavCard(modifier, shape) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
internal fun WebDavMenuRow(title: String, description: String, icon: ImageVector, shape: Shape = RoundedCornerShape(28.dp), enabled: Boolean = true, onClick: () -> Unit) {
    WebDavRow(title, description, icon, Modifier.motionClickable(enabled, onClick), shape) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WebDavToggleRow(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    shape: Shape = RoundedCornerShape(28.dp),
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    WebDavRow(title, description, icon, Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), shape) {
        if(LocalEInkMode.current) Icon(
            if(checked) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null, Modifier.size(48.dp),
            tint = if(checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) else Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun WebDavSection(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}
