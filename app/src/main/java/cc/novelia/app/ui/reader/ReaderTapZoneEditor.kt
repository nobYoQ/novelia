/*
 * 点击分区模型改编自 FBReaderJ v2.5.10 / TapZoneMap.java。
 * 原作者 FBReader.ORG Limited，GPL-2.0-or-later；本项目按 GPL-3.0-only 使用。
 * 本地修改：正文覆盖编辑层、可调分界线、相邻区域合并和逐格编辑。
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.base.AppDialog
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.theme.readerColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** 编辑草稿仅在保存时持久化；取消/返回不会改变阅读设置。 */
@Composable internal fun ReaderTapZoneEditor(
    map: ReaderTapZoneMap, onSave: (ReaderTapZoneMap) -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, staticPagination: Boolean = true, eInk: Boolean = false,
) {
    var draft by remember { mutableStateOf(map) }
    var double by remember { mutableStateOf(false) }
    var individual by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf<List<Int>?>(null) }
    val density = LocalDensity.current
    var topControlsHeight by remember { mutableStateOf(72.dp) }
    var bottomControlsHeight by remember { mutableStateOf(100.dp) }
    val latestDraft by rememberUpdatedState(draft)
    val controlsColor = MaterialTheme.colorScheme.surface.copy(alpha = if(eInk) 1f else .94f)
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(modifier.fillMaxSize().testTag("tap-zone-editor").semantics { paneTitle = "点击分区配置" }
        .pointerInput(Unit) { detectTapGestures {} }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val compact = maxHeight < 400.dp
        ReaderTapZoneGrid(draft, double,
            Modifier.fillMaxSize().pointerInput(Unit) {
                var boundary: Pair<Boolean, Int>? = null
                var fraction = 0f
                detectDragGestures(onDragStart = { position ->
                    val x = latestDraft.columnEdges.drop(1).dropLast(1).withIndex().minByOrNull { abs(it.value * size.width - position.x) }
                    val y = latestDraft.rowEdges.drop(1).dropLast(1).withIndex().minByOrNull { abs(it.value * size.height - position.y) }
                    val dx = x?.let { abs(it.value * size.width - position.x) } ?: Float.MAX_VALUE
                    val dy = y?.let { abs(it.value * size.height - position.y) } ?: Float.MAX_VALUE
                    boundary = when {
                        dx <= 24.dp.toPx() && dx <= dy -> true to (x!!.index + 1)
                        dy <= 24.dp.toPx() -> false to (y!!.index + 1)
                        else -> null
                    }
                    boundary?.let { (column, index) -> fraction = (if(column) latestDraft.columnEdges else latestDraft.rowEdges)[index] }
                }, onDragCancel = { boundary = null }, onDragEnd = { boundary = null }) { change, amount ->
                    boundary?.let { (column, index) ->
                        change.consume()
                        val extent = if(column) size.width else size.height
                        if(extent > 0) {
                            fraction += (if(column) amount.x else amount.y) / extent
                            draft = if(column) latestDraft.withColumnBoundary(index, fraction) else latestDraft.withRowBoundary(index, fraction)
                            fraction = (if(column) draft.columnEdges else draft.rowEdges)[index]
                        }
                    }
                }
            }, individual = individual, staticPagination = staticPagination, eInk = eInk,
            topClearance = if(controlsVisible) topControlsHeight else 0.dp,
            bottomClearance = if(controlsVisible) bottomControlsHeight else 0.dp,
            onSelect = { cell -> selection = if(individual) listOf(cell) else draft.regionFor(cell).cells })
        (1 until draft.columns).forEach { index ->
            val value = draft.columnEdges[index]
            BoundaryHandle("第 $index 列分界", value, Modifier.absoluteOffset {
                IntOffset((value * width).roundToInt() - 12.dp.roundToPx(), (height * .5f).roundToInt() - 20.dp.roundToPx())
            }.size(24.dp, 40.dp).testTag("tap-zone-column-$index")) { draft = draft.withColumnBoundary(index, it) }
        }
        (1 until draft.rows).forEach { index ->
            val value = draft.rowEdges[index]
            BoundaryHandle("第 $index 行分界", value, Modifier.absoluteOffset {
                IntOffset((width * .5f).roundToInt() - 20.dp.roundToPx(), (value * height).roundToInt() - 12.dp.roundToPx())
            }.size(40.dp, 24.dp).testTag("tap-zone-row-$index")) { draft = draft.withRowBoundary(index, it) }
        }
        if(!controlsVisible) Surface(Modifier.align(Alignment.TopCenter), shape = RoundedCornerShape(24.dp),
            color = controlsColor) {
            AppTextButton(onClick = { controlsVisible = true }) { Text("显示配置控件") }
        }
        if(controlsVisible) Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .onSizeChanged { topControlsHeight = with(density) { it.height.toDp() } }, color = controlsColor) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppTextButton(onClick = onDismiss) { Text("取消") }
                    Text("配置点击区域", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    AppTextButton(onClick = { onSave(draft) }, modifier = Modifier.testTag("tap-zone-save")) { Text("保存") }
                }
                if(!compact) Text("拖动分界线调整大小，点按区域设置动作", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if(controlsVisible) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { bottomControlsHeight = with(density) { it.height.toDp() } }, color = controlsColor) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!double, { double = false }, label = { Text("单击") }, modifier = Modifier.testTag("tap-zone-single"))
                    FilterChip(double, { double = true }, label = { Text("双击") }, modifier = Modifier.testTag("tap-zone-double"))
                    AppTextButton(onClick = { individual = !individual }, modifier = Modifier.testTag("tap-zone-individual")) {
                        Text(if(individual) "合并区域" else "逐格编辑")
                    }
                    AppIconButton(onClick = { controlsVisible = false }) { Icon(Icons.Outlined.VisibilityOff, "隐藏配置控件以编辑边缘区域") }
                    if(compact) AppTextButton(onClick = { draft = ReaderTapZoneMap.Default }, modifier = Modifier.testTag("tap-zone-reset")) { Text("重置") }
                }
                if(!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if(individual) "逐格修改后，相同动作会自动合并" else "相邻且单击、双击动作都相同的区域自动合并",
                        Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    AppTextButton(onClick = { draft = ReaderTapZoneMap.Default }, modifier = Modifier.testTag("tap-zone-reset")) { Text("恢复默认") }
                }
            }
        }
    }
    selection?.let { cells ->
        AppSheet(onDismissRequest = { selection = null }) {
          AppScrollColumn(Modifier.fillMaxWidth()) {
            Text("${if(double) "双击" else "单击"} · ${if(cells.size == 1) "单个格子" else "合并区域（${cells.size} 格）"}",
                Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
            ReaderTapAction.editable.forEach { action ->
                ListItem(headlineContent = { Text(action.label(staticPagination)) }, supportingContent = { Text(action.actionHint()) },
                    modifier = Modifier.clickable {
                        draft = draft.withRegionAction(cells, double, action)
                        selection = null
                    }.testTag("tap-zone-option-${action.name}"))
            }
            Spacer(Modifier.height(16.dp))
          }
        }
    }
}

@Composable private fun BoundaryHandle(label: String, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    // 合并区域的标签可能跨过原来的分界线，让标签盖住手柄，避免圆点遮挡文字。
    Box(modifier.zIndex(-1f).semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        setProgress { onChange(it); true }
    }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(12.dp), shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary,
            border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface)) {}
    }
}

/** 配置层和教程共享同一张图；范围与阅读正文视口一致。 */
@Composable internal fun ReaderTapZoneGrid(
    map: ReaderTapZoneMap, double: Boolean, modifier: Modifier = Modifier,
    individual: Boolean = false, staticPagination: Boolean = true, eInk: Boolean = false,
    topClearance: androidx.compose.ui.unit.Dp = 0.dp, bottomClearance: androidx.compose.ui.unit.Dp = 0.dp,
    onSelect: ((Int) -> Unit)? = null,
) {
    val regions = remember(map, individual) {
        if(individual) (0 until map.cellCount).map { cell -> ReaderTapRegion(listOf(cell), listOf(map.cellBounds(cell)),
            map.singleAt(cell % map.columns, cell / map.columns), map.doubleAt(cell % map.columns, cell / map.columns)) }
        else map.mergedRegions()
    }
    val latestSelect by rememberUpdatedState(onSelect)
    val minimumLabelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 12.dp
    BoxWithConstraints(modifier.testTag("tap-zone-grid").pointerInput(map, onSelect != null) {
        if(onSelect != null) detectTapGestures { position -> map.cellAt(position.x, position.y, size.width, size.height)?.let { latestSelect?.invoke(it) } }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            regions.forEach { region ->
                val color = if(eInk) Color.Black else (if(double) region.double else region.single).zoneColor()
                drawTapRegion(region.bounds, color, if(eInk) .04f else .16f, if(eInk) 1f else .7f, 1.5.dp.toPx())
            }
        }
        regions.forEach { region ->
            val outer = ReaderTapRect(region.bounds.minOf { it.left }, region.bounds.minOf { it.top },
                region.bounds.maxOf { it.right }, region.bounds.maxOf { it.bottom })
            val area = region.bounds.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }
            val candidates = if(abs(area - (outer.right - outer.left) * (outer.bottom - outer.top)) < .00001) listOf(outer) else region.bounds
            val rect = candidates.maxBy { candidate ->
                val visibleHeight = ((maxHeight * candidate.bottom).coerceAtMost(maxHeight - bottomClearance) -
                    (maxHeight * candidate.top).coerceAtLeast(topClearance)).value.coerceAtLeast(0f)
                (candidate.right - candidate.left) * visibleHeight
            }
            val top = (maxHeight * rect.top).coerceAtLeast(topClearance)
            val bottom = (maxHeight * rect.bottom).coerceAtMost(maxHeight - bottomClearance)
            if(bottom - top >= minimumLabelHeight) Box(Modifier.absoluteOffset(maxWidth * rect.left, top)
                .size(maxWidth * (rect.right - rect.left), bottom - top).padding(4.dp), contentAlignment = Alignment.Center) {
                val action = if(double) region.double else region.single
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                    modifier = Modifier.testTag("tap-zone-region-${region.cells.first()}").semantics(mergeDescendants = true) {}.then(
                        if(onSelect == null) Modifier else Modifier.clickable { onSelect(region.cells.first()) })) {
                    Text(action.label(staticPagination), Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 全局设置没有打开的正文时，使用相同编辑层在示例正文上配置。 */
@Composable internal fun ReaderTapZonePreview(value: ReaderSettings, onSave: (ReaderTapZoneMap) -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = readerColors(value, MaterialTheme.colorScheme)
        Box(Modifier.fillMaxSize().safeDrawingPadding().background(colors.background)) {
            Text("阅读区域预览\n\n" + "风穿过树梢，旅人沿着小路向前走去。你可以透过半透明区域查看正文，并调整适合自己的点击范围。\n\n".repeat(18),
                Modifier.fillMaxSize().padding(24.dp), color = colors.foreground, style = MaterialTheme.typography.bodyLarge)
            ReaderTapZoneEditor(value.resolvedTapZoneMap, onSave, onDismiss, staticPagination = value.staticPagination, eInk = value.eInkMode)
        }
    }
}

internal fun ReaderTapAction.actionHint(): String = when(this) {
    ReaderTapAction.PreviousPage -> "翻到上一页或上一屏"
    ReaderTapAction.NextPage -> "翻到下一页或下一屏"
    ReaderTapAction.Navigate -> "打开章节目录"
    ReaderTapAction.Menu -> "显示或收起阅读工具栏"
    ReaderTapAction.None -> "不执行点击动作，仍可滑动或长按"
}
