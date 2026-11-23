package cc.novelia.app.ui.shelf

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 一次拖动以起点之前的选择为基准；回划时恢复离开区间的条目，保留原有选择。 */
internal fun shelfRangeSelection(keys: List<String>, baseline: Set<String>, anchor: String, target: String, selecting: Boolean): Set<String> {
    val start = keys.indexOf(anchor)
    val end = keys.indexOf(target)
    if(start < 0 || end < 0) return baseline
    val range = keys.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
    return if(selecting) baseline + range else baseline - range
}

@Stable internal class ShelfDragSelectionState(
    private val list: LazyListState, private val keys: () -> List<String>,
    private val selection: () -> Set<String>, private val managing: () -> Boolean,
    private val begin: () -> Unit, private val change: (Set<String>) -> Unit,
    private val customHit: () -> ((Offset, Boolean) -> String?)? = { null },
    private val reorderKeys: () -> List<String>,
    private val move: (String, String, Boolean) -> Unit,
    private val touchSlop: Float,
) {
    var dragging by mutableStateOf(false)
        private set
    private var anchor: String? = null
    private var baseline = emptySet<String>()
    private var selecting = true
    private var startPosition = Offset.Zero
    private var movingBook = false
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var draggedBook by mutableStateOf<String?>(null)
        private set
    var dropTarget by mutableStateOf<String?>(null)
        private set

    fun selectOne(key: String) {
        if(key !in keys()) return
        val current = if(managing()) selection() else emptySet()
        begin()
        change(if(key in current) current - key else current + key)
    }

    fun start(position: Offset) {
        val key = hit(position, nearest = false) ?: return
        anchor = key
        baseline = if(managing()) selection() else emptySet()
        selecting = key !in baseline
        pointer = position
        startPosition = position
        dragging = true
        begin()
        if(reorderKeys().size > 1 && key in reorderKeys()) {
            draggedBook = key
            change(baseline + key)
        } else updateRange()
    }

    private fun hit(position: Offset, nearest: Boolean, available: List<String> = keys()): String? {
        customHit()?.let { return it(position, nearest)?.takeIf { key -> key in available } }
        val layout = list.layoutInfo
        val y = position.y + layout.viewportStartOffset
        val availableKeys = available.toHashSet()
        val visible = layout.visibleItemsInfo.filter { it.key in availableKeys }
        val item = visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if(nearest) visible.minByOrNull { if(y < it.offset) it.offset - y else y - it.offset - it.size } else null
        return item?.key as? String
    }

    fun drag(position: Offset) {
        if(!dragging) return
        pointer = position
        if(draggedBook != null && !movingBook) {
            if((pointer - startPosition).getDistance() < touchSlop) return
            movingBook = true
        }
        updateDrag()
    }

    private fun updateDrag() {
        val key = draggedBook
        if(key == null) updateRange()
        else dropTarget = hit(pointer, nearest = true, available = reorderKeys())?.takeUnless { it == key }
    }

    private fun updateRange() {
        val start = anchor ?: return
        val currentKeys = keys()
        if(start !in currentKeys) { finish(); return }
        val target = hit(pointer, nearest = true) ?: return
        change(shelfRangeSelection(currentKeys, baseline, start, target, selecting))
    }

    suspend fun scrollAtEdge(edge: Float, maxStep: Float) {
        if(!dragging || (draggedBook != null && !movingBook)) return
        val layout = list.layoutInfo
        val above = pointer.y
        val below = layout.viewportSize.height - pointer.y
        val step = when {
            above < edge -> -maxStep * (1f - above / edge).coerceIn(0f, 1f)
            below < edge -> maxStep * (1f - below / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if(step != 0f) { list.scrollBy(step); updateDrag() }
    }

    fun finish() {
        val key = draggedBook
        val target = dropTarget
        if(key != null && target != null) {
            val order = reorderKeys()
            val from = order.indexOf(key)
            val to = order.indexOf(target)
            if(from >= 0 && to >= 0) {
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                move(key, target, to > from)
            }
        } else if(key != null && !selecting) change(baseline - key)
        cancel()
    }

    fun cancel() {
        dragging = false
        anchor = null
        baseline = emptySet()
        draggedBook = null
        dropTarget = null
        movingBook = false
    }

    fun reorderActions(key: String): List<CustomAccessibilityAction> {
        val order = reorderKeys()
        val index = order.indexOf(key)
        if(index < 0) return emptyList()
        return buildList {
            order.getOrNull(index - 1)?.let { target ->
                add(CustomAccessibilityAction("上移一位") { move(key, target, false); true })
            }
            order.getOrNull(index + 1)?.let { target ->
                add(CustomAccessibilityAction("下移一位") { move(key, target, true); true })
            }
        }
    }
}

@Composable internal fun rememberShelfDragSelection(
    list: LazyListState, keys: List<String>, selection: Set<String>, managing: Boolean, enabled: Boolean,
    onBegin: () -> Unit, onSelection: (Set<String>) -> Unit,
    hitTest: ((Offset, Boolean) -> String?)? = null,
    reorderKeys: List<String> = emptyList(),
    onReorder: (String, String, Boolean) -> Unit = { _, _, _ -> },
): ShelfDragSelectionState {
    val currentKeys by rememberUpdatedState(keys)
    val currentSelection by rememberUpdatedState(selection)
    val currentManaging by rememberUpdatedState(managing)
    val begin by rememberUpdatedState(onBegin)
    val change by rememberUpdatedState(onSelection)
    val hit by rememberUpdatedState(hitTest)
    val currentReorderKeys by rememberUpdatedState(reorderKeys)
    val move by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val state = remember(list, touchSlop) { ShelfDragSelectionState(list, { currentKeys }, { currentSelection }, { currentManaging },
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress); begin() }, { change(it) }, { hit },
        { currentReorderKeys }, { key, target, after -> move(key, target, after) }, touchSlop) }
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 10.dp.toPx() }
    LaunchedEffect(enabled, state.dragging, edge, speed) {
        if(!enabled) state.cancel()
        else if(state.dragging) {
            var lastFrame = withFrameNanos { it }
            while(state.dragging) {
                val frame = withFrameNanos { it }
                state.scrollAtEdge(edge, speed * ((frame - lastFrame) / 16_666_667f).coerceIn(0f, 3f))
                lastFrame = frame
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

internal fun Modifier.shelfDragSelection(state: ShelfDragSelectionState, enabled: Boolean): Modifier =
    if(!enabled) this else pointerInput(state) {
        detectDragGesturesAfterLongPress(onDragStart = state::start, onDragEnd = state::finish,
            onDragCancel = state::cancel, onDrag = { change, _ ->
                if(state.dragging) { change.consume(); state.drag(change.position) }
            })
    }

@Composable internal fun ShelfReorderPreview(title: String, drag: ShelfDragSelectionState) {
    Layout(modifier = Modifier.clearAndSetSemantics {}, content = {
        Surface(Modifier.widthIn(max = 240.dp), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Text(title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) { measurables, constraints ->
        // 动效容器可能传入视口大小的最小约束，只有透明层应当填满视口。
        val preview = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val x = (drag.pointer.x - preview.width / 2f).roundToInt()
                .coerceIn(0, width - preview.width)
            val y = (drag.pointer.y - preview.height - 12.dp.toPx()).roundToInt()
                .coerceIn(0, height - preview.height)
            preview.place(x, y)
        }
    }
}
