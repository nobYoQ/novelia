@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cc.novelia.app.ui.components.AppDialog
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.LocalInAppSheet
import cc.novelia.app.ui.components.LocalPanelSession
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun ReaderSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}

/** 调整外观时保留大部分正文可见，其他阅读器弹层仍采用完整高度。 */
@Composable internal fun ReaderPreferencesSheet(onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val session = remember { Any() }
    val reducedMotion = appReducedMotion()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val targetHeight = if(expanded) screenHeight * .85f else screenHeight * .48f
    val animatedHeight by animateDpAsState(targetHeight,
        if(reducedMotion) snap() else spring(dampingRatio = .72f, stiffness = 280f), label = "reader panel height")
    val sheetHeight = if(reducedMotion) targetHeight else animatedHeight
    val resizeGesture = Modifier.readerPreferencesResizeGesture(expanded, { expanded = it }, onDismissRequest)
    if(!reducedMotion) ModalBottomSheet(onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 高度切换由阅读偏好管理，避免大半屏下拖直接触发底层弹层关闭。
        sheetGesturesEnabled = false,
        scrimColor = Color.Transparent, dragHandle = {
            Box(Modifier.fillMaxWidth().then(resizeGesture).testTag("reader-preferences-drag-handle"), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
        }) {
        CompositionLocalProvider(LocalInAppSheet provides true, LocalPanelSession provides session) {
            Column(Modifier.fillMaxWidth().height(sheetHeight).then(resizeGesture).testTag("reader-preferences-panel")) { content(expanded) { expanded = it } }
        }
    } else AppDialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().height(sheetHeight).then(resizeGesture).testTag("reader-preferences-panel"), shape = MaterialTheme.shapes.extraLarge) {
                CompositionLocalProvider(LocalInAppSheet provides true, LocalPanelSession provides session) {
                    Column { content(expanded) { expanded = it } }
                }
            }
        }
    }
}

/** 子级列表和滑条优先处理手势，仅接管标题、拖动条等未消费的竖向拖动。 */
@Composable private fun Modifier.readerPreferencesResizeGesture(
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onDismissRequest: () -> Unit
): Modifier {
    val latestExpanded by rememberUpdatedState(expanded)
    val latestChange by rememberUpdatedState(onExpandedChange)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val dismissThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    return pointerInput(threshold, dismissThreshold) {
        var distance = 0f
        var startedExpanded = false
        detectVerticalDragGestures(
            onDragStart = { distance = 0f; startedExpanded = latestExpanded },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                when {
                    distance <= -threshold -> latestChange(true)
                    startedExpanded && distance >= threshold -> latestChange(false)
                    !startedExpanded && distance >= dismissThreshold -> latestDismiss()
                }
                distance = 0f
            }
        ) { change, amount ->
            change.consume()
            distance += amount
        }
    }
}

@Composable internal fun ReaderSheetExpandIcon(expanded: Boolean) {
    val reduced = appReducedMotion()
    val rotation by animateFloatAsState(if(expanded) 180f else 0f,
        if(reduced) snap() else spring(dampingRatio = .72f, stiffness = 280f), label = "reader panel arrow")
    Icon(Icons.Outlined.ExpandLess, if(expanded) "收起面板" else "展开面板",
        Modifier.graphicsLayer { rotationZ = if(reduced) if(expanded) 180f else 0f else rotation })
}
