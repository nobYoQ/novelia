package cc.novelia.app.ui.components.base

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun PanelChoiceRow(label: String, options: List<String>, index: Int,
    modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var open by remember(enabled) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { open = true }
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(options.getOrElse(index) { options.first() }, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge, color = if(enabled) colors.primary else colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                if(enabled) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
            AppDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                options.forEachIndexed { optionIndex, option ->
                    DropdownMenuItem(text = { Text(option) }, modifier = Modifier.semantics { selected = optionIndex == index },
                        trailingIcon = if(optionIndex == index) ({ Icon(Icons.Outlined.Check, null) }) else null,
                        onClick = { open = false; onSelect(optionIndex) })
                }
            }
        }
    }
}
