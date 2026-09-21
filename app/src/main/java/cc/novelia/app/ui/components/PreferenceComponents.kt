@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.motionClickable

@Composable fun SectionTitle(title: String, detail: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if(detail != null) TextButton(onClick = onClick) { Text(detail) }
    }
}
@Composable fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.labelLarge
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(options.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Measure actual labels so long translations and large fonts get fewer columns.
            val widestLabel = options.maxOf { measurer.measure(it, textStyle, softWrap = false).size.width }
            val preferredWidth = (with(density) { widestLabel.toDp() } + 32.dp).coerceAtLeast(88.dp)
            val maxColumns = ((maxWidth + ChipSpacing) / (preferredWidth + ChipSpacing)).toInt().coerceIn(1, minOf(3, options.size))
            val rowCount = (options.size + maxColumns - 1) / maxColumns
            val columns = (options.size + rowCount - 1) / rowCount
            Column(verticalArrangement = Arrangement.spacedBy(ChipSpacing)) {
                options.chunked(columns).forEachIndexed { row, titles ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipSpacing)) {
                        titles.forEachIndexed { column, title ->
                            val index = row * columns + column
                            key(index, title) {
                                AppSelectionChip(selected == index, { onSelect(index) },
                                    label = { Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        repeat(columns - titles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
@Composable fun MenuRow(title: String, description: String, icon: ImageVector, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { if(description.isNotEmpty()) Text(description) }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = trailing, modifier = Modifier.motionClickable(onClick = onClick).heightIn(min = 64.dp))
}
@Composable fun TextPrompt(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 6) }, confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, confirmLabel: String, onConfirm: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) } }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}
