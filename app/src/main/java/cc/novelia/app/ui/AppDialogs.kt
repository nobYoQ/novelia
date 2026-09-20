package cc.novelia.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Window animations are outside Compose's animation scale and must be disabled separately. */
@Composable private fun ApplyDialogMotion() {
    val reduced = appReducedMotion()
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val defaultAnimations = remember(window) { window?.attributes?.windowAnimations ?: 0 }
    SideEffect { window?.setWindowAnimations(if(reduced) 0 else defaultAnimations) }
}

@Composable internal fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties) {
        ApplyDialogMotion()
        content()
    }
}

@Composable internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(onDismissRequest, confirmButton = {
        ApplyDialogMotion()
        confirmButton()
    }, modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text,
        properties = properties)
}

/** A static anchored popup avoids Material's hard-coded menu fade/scale in reduced motion. */
@Composable internal fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if(!appReducedMotion()) {
        DropdownMenu(expanded, onDismissRequest, modifier, scrollState = scrollState, content = content)
    } else if(expanded) {
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        val position = remember(margin) { StaticMenuPosition(margin) }
        Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)) {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 3.dp) {
                Column(modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp)
                    .verticalScroll(scrollState).padding(vertical = 8.dp), content = content)
            }
        }
    }
}

internal class StaticMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = if(layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val below = anchorBounds.bottom
        val top = if(below + popupContentSize.height <= windowSize.height - margin) below
            else anchorBounds.top - popupContentSize.height
        return IntOffset(
            left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            top.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
        )
    }
}
