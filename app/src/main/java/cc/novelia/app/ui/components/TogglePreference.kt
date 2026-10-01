@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.LocalEInkMode

@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) =
    TogglePreference(title, subtitle, value, null, onChange)

@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, defaultValue: Boolean?, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val changed = defaultValue != null && value != defaultValue
    ListItem(headlineContent = { Text(title) }, supportingContent = if(subtitle.isNotBlank()) ({ Text(subtitle) }) else null, trailingContent = {
        if(LocalEInkMode.current) Icon(if(value) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null, Modifier.size(48.dp),
            tint = if(changed || (defaultValue == null && value)) scheme.primary else scheme.onSurfaceVariant)
        else Switch(value, onCheckedChange = null, colors = if(defaultValue == null) SwitchDefaults.colors() else SwitchDefaults.colors(
            checkedTrackColor = if(changed) scheme.primary else scheme.primary.copy(alpha = .22f),
            checkedThumbColor = if(changed) scheme.onPrimary else scheme.primary,
            uncheckedTrackColor = if(changed) scheme.primary.copy(alpha = .22f) else scheme.surfaceContainerHighest,
            uncheckedThumbColor = if(changed) scheme.primary else scheme.outline,
            uncheckedBorderColor = if(changed) scheme.primary else scheme.outlineVariant))
    }, modifier = Modifier.heightIn(min = 48.dp).semantics {
        if(defaultValue != null) stateDescription = "${if(changed) "已修改" else "与默认一致"}，默认${if(defaultValue) "开启" else "关闭"}"
    }.toggleable(value = value, role = Role.Switch, onValueChange = onChange))
}
