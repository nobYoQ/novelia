@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.motionClickable
import cc.novelia.app.ui.theme.pressFeedback

@Composable fun SectionTitle(title: String, detail: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if(detail != null) TextButton(onClick = onClick) { Text(detail) }
    }
}
@Composable fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val reducedMotion = appReducedMotion()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, title ->
                key(i, title) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val checked = selected == i
                    val checkProgress = animateFloatAsState(if(checked) 1f else 0f, tween(if(reducedMotion) 0 else AppMotion.Quick), label = "choice check")
                    FilterChip(
                        checked, onClick = { onSelect(i) }, label = { Text(title) },
                        modifier = Modifier.pressFeedback(interactionSource), interactionSource = interactionSource,
                        leadingIcon = {
                            // Reserve the slot so selecting a chip cannot move its neighbours.
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp).graphicsLayer {
                                val progress = if(reducedMotion) if(checked) 1f else 0f else checkProgress.value
                                alpha = progress
                                scaleX = .7f + .3f * progress
                                scaleY = scaleX
                            })
                        },
                    )
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
@Composable fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
