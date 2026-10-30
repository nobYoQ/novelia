package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppTextButton

@Composable internal fun ReaderFootnoteDialog(body: String, onDismiss: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("脚注") }, text = {
        SelectionContainer { Text(body, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }
    }, confirmButton = { AppTextButton(onClick = onDismiss) { Text("关闭") } })
}
