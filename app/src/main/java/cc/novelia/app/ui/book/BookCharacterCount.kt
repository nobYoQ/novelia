package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.formatApproximateCharacters
import cc.novelia.app.data.catalog.formatExactCharacters
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.theme.motionClickable

@Composable internal fun BookCharacterCount(characters: Long?) {
    val count = characters?.takeIf { it >= 0 }
    var expanded by rememberSaveable(count) { mutableStateOf(false) }
    Text(count?.let(::formatApproximateCharacters) ?: "字数未知",
        modifier = Modifier.testTag("book-character-count").then(if(count != null) Modifier.motionClickable(onClick = { expanded = true }) else Modifier),
        style = MaterialTheme.typography.bodyMedium,
        color = if(count != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textDecoration = if(count != null) TextDecoration.Underline else null)
    if(expanded && count != null) AppAlertDialog(onDismissRequest = { expanded = false },
        title = { Text("作品字数") },
        text = { SelectionContainer { Text(formatExactCharacters(count), Modifier.testTag("exact-character-count")) } },
        confirmButton = { TextButton(onClick = { expanded = false }, Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
}
