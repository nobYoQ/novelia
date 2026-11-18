package cc.novelia.app.ui.components.base

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.appRoundedCornerShape

@Composable internal fun <T> PanelRadioGrid(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = appRoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().padding(6.dp)) {
            val columns = if(maxWidth >= (300 * LocalDensity.current.fontScale).dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (value, title) ->
                            val checked = value == selected
                            Row(Modifier.weight(1f).clip(appRoundedCornerShape(14.dp))
                                .background(if(checked) colors.secondaryContainer else Color.Transparent)
                                .selectable(checked, role = Role.RadioButton, onClick = { onSelect(value) })
                                .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if(checked) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if(checked) colors.onSecondaryContainer else colors.onSurface)
                                AppRadioButton(checked, onClick = null)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
