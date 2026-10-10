package cc.novelia.app.ui.notes

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Note
import cc.novelia.app.ui.components.AppAlertDialog

/** 空笔记仍保留书签，清空笔记内容不删除其阅读位置。 */
@Composable internal fun NoteEditorDialog(note: Note, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(note.id) { mutableStateOf(note.text) }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("编辑笔记") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(note.quote, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("笔记内容") },
                supportingText = { Text("留空仅保留书签") }, minLines = 3, maxLines = 6)
        }
    }, confirmButton = {
        AppTextButton(onClick = { onSave(text.trim()); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存笔记") }
    }, dismissButton = {
        AppTextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
    })
}
