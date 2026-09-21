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
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.LocalEInkMode

@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = if(subtitle.isNotBlank()) ({ Text(subtitle) }) else null, trailingContent = {
    if(LocalEInkMode.current) Icon(if(value) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null, Modifier.size(48.dp), tint = if(value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    else Switch(value, onCheckedChange = null)
}, modifier = Modifier.heightIn(min = 48.dp).toggleable(value = value, role = Role.Switch, onValueChange = onChange)) }
