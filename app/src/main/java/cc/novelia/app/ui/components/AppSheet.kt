@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cc.novelia.app.ui.theme.appReducedMotion

internal val LocalInAppSheet = compositionLocalOf { false }

@Composable internal fun AppSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    if (!appReducedMotion()) ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        CompositionLocalProvider(LocalInAppSheet provides true) { content() }
    }
    else AppDialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column {
                TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭面板") }
                HorizontalDivider()
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}
