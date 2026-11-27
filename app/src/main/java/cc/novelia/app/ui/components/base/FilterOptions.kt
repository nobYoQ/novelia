package cc.novelia.app.ui.components.base

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.appShape

@Composable internal fun FilterOptions(title: String, labels: List<String>, selectedIndices: Set<Int>, tag: String,
    preferredColumns: Int = 4, multiple: Boolean = false, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val longest = labels.maxOfOrNull { it.length } ?: 1
            val minimum = ((longest * 13 + 24) * LocalDensity.current.fontScale).dp
            val fit = ((maxWidth + 8.dp) / (minimum + 8.dp)).toInt().coerceIn(1, preferredColumns.coerceAtMost(labels.size).coerceAtLeast(1))
            val columns = if(labels.size == 4 && fit == 3) 2 else fit
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.indices.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { index ->
                            val chosen = index in selectedIndices
                            Surface(onClick = { onSelect(index) }, shape = appShape(RoundedCornerShape(10.dp)),
                                color = if(chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if(chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = if(chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("$tag-${labels[index]}").semantics {
                                    selected = chosen; role = if(multiple) Role.Checkbox else Role.RadioButton
                                }) {
                                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(labels[index], style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
