package cc.novelia.app.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.ReaderTapZoneMap
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppTextButton

/** 使用当前配置覆盖正文，分区位置、合并结果与真实命中范围一致。 */
@Composable internal fun ReaderTapTutorial(
    staticPagination: Boolean, eInk: Boolean, readingInsets: WindowInsets,
    readingStatusBarHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier, map: ReaderTapZoneMap = ReaderTapZoneMap.Default, onDismiss: () -> Unit,
) {
    var double by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    var topControlsHeight by remember { mutableStateOf(48.dp) }
    var bottomControlsHeight by remember { mutableStateOf(56.dp) }
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(modifier.background(if(eInk) Color.White.copy(alpha = .85f) else Color.Black.copy(alpha = .12f))
        .testTag("reader-tap-tutorial").semantics { paneTitle = "点击区域翻页引导" }
        .pointerInput(Unit) { detectTapGestures {} }) {
        val compact = maxHeight < 400.dp
        Box(Modifier.align(Alignment.TopCenter).fillMaxHeight().windowInsetsPadding(readingInsets)
            .padding(bottom = readingStatusBarHeight).fillMaxWidth()) {
            ReaderTapZoneGrid(map, double, staticPagination = staticPagination, eInk = eInk, modifier = Modifier.fillMaxSize(),
                topClearance = if(controlsVisible) topControlsHeight else 0.dp,
                bottomClearance = if(controlsVisible) bottomControlsHeight else 0.dp)
            if(!controlsVisible) Surface(Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                AppTextButton(onClick = { controlsVisible = true }) { Text("显示教程控件") }
            }
            if(controlsVisible) Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { topControlsHeight = with(density) { it.height.toDp() } }, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("点击区域翻页", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    AppIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "关闭点击翻页引导") }
                }
            }
            if(controlsVisible) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomControlsHeight = with(density) { it.height.toDp() } }, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if(!compact) Text("这里展示你当前设置的区域。相同动作的邻格合并显示；可在阅读设置中拖动分界线调整大小。",
                        Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!double, { double = false }, label = { Text("单击") })
                        FilterChip(double, { double = true }, label = { Text("双击") })
                        AppTextButton(onClick = onDismiss) { Text("开始阅读") }
                        AppIconButton(onClick = { controlsVisible = false }) { Icon(Icons.Outlined.VisibilityOff, "隐藏教程控件以查看完整分区") }
                    }
                }
            }
        }
    }
}
