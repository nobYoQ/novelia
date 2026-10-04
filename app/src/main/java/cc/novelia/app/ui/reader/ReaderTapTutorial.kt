package cc.novelia.app.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 与正文同级的引导层：不改变视口尺寸，关闭动作也不会穿透为一次翻页。 */
@Composable internal fun ReaderTapTutorial(
    staticPagination: Boolean, eInk: Boolean, readingInsets: WindowInsets,
    bodyWidth: Float, pageProgressHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier, onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val foreground = if(eInk) Color.Black else Color.White
    val scrim = if(eInk) Color.White else Color(0xFF10221B).copy(alpha = .88f)
    BoxWithConstraints(modifier.background(scrim).testTag("reader-tap-tutorial").focusRequester(focus).focusable()
        .semantics { paneTitle = "点击区域翻页引导" }
        .pointerInput(Unit) {
            // 子按钮先处理 Main 阶段；一旦接下按压，就保留整段手势给按钮。
            // 其余区域由引导层接住，不让触摸落入正文。
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                if(!down.isConsumed) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                    } while(event.changes.any { it.pressed })
                }
            }
        }) {
        val compact = maxHeight < 400.dp
        Column(Modifier.fillMaxSize().windowInsetsPadding(readingInsets)
            .padding(bottom = if(staticPagination) pageProgressHeight else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = if(compact) 0.dp else 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("点击区域翻页", Modifier.weight(1f).semantics { heading() }, color = foreground, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, "关闭点击翻页引导", tint = foreground)
                    }
                }
                if(!compact) Text("点击左右区域前后翻页，点击中间显示或收起工具栏。", color = foreground.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium)
            }
            // 分区与正文宽度一致，说明和关闭按钮留在分区线之外。
            Row(Modifier.weight(1f).padding(vertical = if(compact) 8.dp else 16.dp).widthIn(max = bodyWidth.dp).fillMaxWidth()) {
                listOf(-1, 0, 1).forEachIndexed { index, direction ->
                    Column(Modifier.weight(1f).fillMaxHeight().background(foreground.copy(alpha = if(eInk) 0f else if(direction == 0) .025f else .065f))
                        .drawBehind { if(index > 0) drawLine(foreground.copy(alpha = if(eInk) 1f else .24f), Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }
                        .padding(horizontal = 6.dp).testTag("reader-tap-guide-${if(direction < 0) "previous" else if(direction > 0) "next" else "menu"}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                        if(!compact) Text(when(direction) { -1 -> "左侧 1/3"; 1 -> "右侧 1/3"; else -> "中间 1/3" },
                            color = foreground.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        Icon(when(direction) { -1 -> Icons.Outlined.ChevronLeft; 1 -> Icons.Outlined.ChevronRight; else -> Icons.Outlined.Menu },
                            null, Modifier.size(if(compact) 24.dp else 32.dp), tint = foreground)
                        Text(when(direction) { -1 -> if(staticPagination) "上一页" else "上一屏"; 1 -> if(staticPagination) "下一页" else "下一屏"; else -> "工具栏" },
                            color = foreground, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                }
            }
            if(!compact) Column(Modifier.widthIn(max = 480.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保留原有滑动、长按和插图操作。\n关闭后不再自动提示。", color = foreground.copy(alpha = .85f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                OutlinedButton(onClick = onDismiss, border = androidx.compose.foundation.BorderStroke(1.dp, foreground.copy(alpha = .6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground), modifier = Modifier.heightIn(min = 48.dp)) { Text("开始阅读") }
            }
        }
    }
}
