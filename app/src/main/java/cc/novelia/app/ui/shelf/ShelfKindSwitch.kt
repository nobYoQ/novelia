package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 同样的低对比底座与分段选中态；窄屏、大字模式下保持标签完整。 */
@Composable internal fun ShelfKindSwitch(labels: List<String>, selectedIndex: Int, tag: String, onChange: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag(tag),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.padding(4.dp).selectableGroup()) {
            val fontScale = LocalDensity.current.fontScale
            val columns = (maxWidth / (64 * fontScale).dp).toInt().coerceIn(1, labels.size)
            Column {
                labels.chunked(columns).forEachIndexed { row, values ->
                    Row {
                        values.forEachIndexed { column, label ->
                            val index = row * columns + column
                            TextButton(onClick = { if(selectedIndex != index) onChange(index) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = selectedIndex == index },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if(selectedIndex == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    contentColor = if(selectedIndex == index) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        repeat(columns - values.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
