package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.SavedSearchPreset
import cc.novelia.app.ui.components.base.AppAlertDialog

@Composable internal fun SaveSearchPresetDialog(preset: SavedSearchPreset, onDismiss: () -> Unit, onSave: (SavedSearchPreset) -> Unit) {
    var name by rememberSaveable(preset.id) { mutableStateOf(preset.name) }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("保存搜索组合") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if(it.length <= 80) name = it }, label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), supportingText = { Text("${name.length} / 80") })
                Text(preset.summary(), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { AppTextButton(enabled = name.isNotBlank(), onClick = { onSave(preset.copy(name = name).normalized()) }) { Text("保存") } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("取消") } })
}
