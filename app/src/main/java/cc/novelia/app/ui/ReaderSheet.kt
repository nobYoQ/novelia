@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

val LocalEInkMode = staticCompositionLocalOf { false }

@Composable internal fun ReaderSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}

@Composable internal fun AppSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    if (!appReducedMotion()) ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, content = content)
    else AppDialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column {
                TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) { Text("关闭面板") }
                HorizontalDivider()
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}
