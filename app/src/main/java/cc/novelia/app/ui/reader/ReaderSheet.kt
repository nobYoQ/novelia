@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cc.novelia.app.ui.components.AppDialog
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.LocalInAppSheet
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun ReaderSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}

/** Keep most of the page visible while adjusting its appearance. Other reader sheets remain full size. */
@Composable internal fun ReaderPreferencesSheet(onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetHeight = if(expanded) screenHeight * .85f else screenHeight * .48f
    if(!appReducedMotion()) ModalBottomSheet(onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        CompositionLocalProvider(LocalInAppSheet provides true) {
            Column(Modifier.fillMaxWidth().height(sheetHeight)) { content(expanded) { expanded = it } }
        }
    } else AppDialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().height(sheetHeight), shape = MaterialTheme.shapes.extraLarge) {
                CompositionLocalProvider(LocalInAppSheet provides true) {
                    Column { content(expanded) { expanded = it } }
                }
            }
        }
    }
}
