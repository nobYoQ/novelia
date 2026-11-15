@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.AppSlider
import cc.novelia.app.ui.theme.themeRgb
import kotlin.math.roundToInt

internal val accentSwatches = listOf(0x3E6B3BL, 0x16796F, 0x4766AF, 0x8454A3, 0xA44668, 0xA96927)
internal val lightSwatches = listOf(0xFFFFFFL, 0xF1F4EC, 0xF7EDDA, 0xE2F1EF, 0xE8EDF9, 0xF6E8EE)
internal val darkSwatches = listOf(0x101512L, 0x232524, 0x2A241B, 0x172C2C, 0x1D2537, 0x32232D)

@Composable internal fun ThemeChoice(options: List<String>, selected: Int, choose: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(index == selected, onClick = { choose(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size), label = { Text(label) })
        }
    }
}

@Composable internal fun ThemeColorControl(label: String, value: Long, swatches: List<Long>,
    onPick: () -> Unit, onSwatch: (Long) -> Unit) {
    ProfileDetailCard {
        Surface(onClick = onPick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(value)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text("#%06X".format(value), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.Colorize, "自选颜色")
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp).selectableGroup()) {
            swatches.forEach { rgb ->
                val color = themeRgb(rgb)
                val chosen = value == rgb
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(chosen, role = Role.RadioButton, onClick = { onSwatch(rgb) })
                    .semantics { contentDescription = "色值 #%06X".format(rgb) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(30.dp).background(color, CircleShape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape), contentAlignment = Alignment.Center) {
                        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = if (color.luminance() > .18f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

@Composable internal fun ThemeSlider(label: String, value: Int, min: Int = 0, change: (Int) -> Unit) {
    ProfileDetailCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("$value%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            AppSlider(value.toFloat(), { change(it.roundToInt()) }, valueRange = min.toFloat()..100f, steps = (100 - min) / 5 - 1,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}
