@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

val LocalEInkMode = staticCompositionLocalOf { false }

@Composable internal fun ReaderSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalEInkMode.current) ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
    else Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setWindowAnimations(0) }
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column {
                TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) { Text("关闭面板") }
                HorizontalDivider()
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}
