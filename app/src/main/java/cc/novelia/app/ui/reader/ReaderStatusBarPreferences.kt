package cc.novelia.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReaderStatusItems
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalThemePreset
import cc.novelia.app.ui.theme.readerColors
import kotlin.math.abs

private fun List<String>.moveItem(item: String, to: Int): List<String> =
    if(to !in indices || indexOf(item) == to) this else toMutableList().apply { remove(item); add(to, item) }

@Composable internal fun ReaderStatusBarPreferences(settings: ReaderSettings, top: Boolean, defaults: ReaderSettings?, onChange: (List<String>) -> Unit) {
    val items = if(top) settings.resolvedTopReadingStatusItems else settings.resolvedReadingStatusItems
    val prefix = if(top) "reader-top-status" else "reader-status"
    val eInk = settings.eInkMode || LocalEInkMode.current
    var dragging by remember(items, top, eInk) { mutableStateOf<String?>(null) }
    var target by remember { mutableIntStateOf(-1) }
    var distance by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    val commit by rememberUpdatedState(onChange)
    val order = dragging?.let { items.moveItem(it, target) } ?: items
    fun center(list: List<String>, item: String) = list.takeWhile { it != item }.sumOf { heights[it] ?: 0 } + (heights[item] ?: 0) / 2f
    ReaderBarsPreview(if(top) settings.copy(topReadingStatusItems = order) else settings.copy(readingStatusItems = order))
    (order + ReaderStatusItems.labels.keys.filterNot { it in items }).forEach { item -> key(item) {
        val index = items.indexOf(item)
        val selected = index >= 0
        val label = ReaderStatusItems.labels.getValue(item)
        fun move(delta: Int): Boolean {
            if(!selected || index + delta !in items.indices) return false
            commit(items.moveItem(item, index + delta)); return true
        }
        val moving = dragging == item
        AppListItem(modifier = Modifier.testTag("$prefix-option-$item").onSizeChanged { heights[item] = it.height }
            .zIndex(if(moving) 1f else 0f).graphicsLayer {
                translationY = if(moving) center(items, item) + distance - center(order, item) else 0f
            }.semantics {
                if(selected && !eInk) customActions = buildList {
                    if(index > 0) add(CustomAccessibilityAction("$label 前移") { move(-1) })
                    if(index < items.lastIndex) add(CustomAccessibilityAction("$label 后移") { move(1) })
                }
            }.toggleable(selected, role = Role.Checkbox, onValueChange = { commit(if(it) items + item else items - item) }),
            leadingContent = { AppCheckbox(checked = selected, onCheckedChange = null) }, headlineContent = { Text(label) },
            trailingContent = { if(selected && eInk) Row {
                AppIconButton(onClick = { move(-1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "$label 前移") }
                AppIconButton(onClick = { move(1) }, enabled = index < items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "$label 后移") }
            } else if(selected) Box(Modifier.size(48.dp).testTag("$prefix-drag-$item").pointerInput(items, item) {
                detectVerticalDragGestures(onDragStart = { target = index; distance = 0f; dragging = item },
                    onDragCancel = { dragging = null }, onDragEnd = {
                        val result = items.moveItem(item, target)
                        dragging = null
                        if(result != items) commit(result)
                    }) { change, delta ->
                    change.consume()
                    val start = center(items, item)
                    distance = (distance + delta).coerceIn(center(items, items.first()) - start, center(items, items.last()) - start)
                    target = items.indices.minBy { abs(center(items, items[it]) - start - distance) }
                }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.DragHandle, "拖动排序：$label") } })
    } }
    val reset = if(top) defaults?.resolvedTopReadingStatusItems ?: ReaderStatusItems.topDefaults else defaults?.resolvedReadingStatusItems ?: ReaderStatusItems.defaults
    AppTextButton(onClick = { onChange(reset) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if(top) "恢复默认顶栏内容" else "恢复默认底栏内容") }
}

@Composable private fun ReaderBarsPreview(settings: ReaderSettings) {
    val colors = readerColors(settings, MaterialTheme.colorScheme, LocalThemePreset.current != null)
    val example = remember { ReaderBarState("山海之间", "第三章 · 潮声", .42f, true, 12, 4) }
    val devices = remember { ReaderBarDevices(mutableStateOf("08:24"), mutableStateOf(ReaderBatteryLevel(86, true))) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.background).testTag("reader-bars-preview")) {
            val columns = ReaderStatusItems.columns(maxWidth.value, LocalDensity.current.fontScale)
            val bar = Modifier.fillMaxWidth().background(colors.toolbar.copy(alpha = 1f - settings.resolvedToolbarTransparency))
            Column {
                ReaderReadingStatusBar(example, settings, columns, colors.foreground, bar, top = true, devices = devices, tagPrefix = "preview-top-")
                Text("潮声渐近，书页又翻过了一页。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
                ReaderReadingStatusBar(example, settings, columns, colors.foreground, bar, devices = devices, tagPrefix = "preview-bottom-")
            }
        }
    }
}
