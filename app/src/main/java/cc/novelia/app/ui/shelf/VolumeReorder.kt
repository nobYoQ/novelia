package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.AppIconButton

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.theme.LocalEInkMode
import kotlin.math.abs

/** 排序草稿仅在手势期间存在，完成一次拖放只持久化一次。 */
internal class VolumeReorderState(
    private val list: LazyListState,
    private val sourceRows: List<ShelfRowItem>,
    private val onCommit: (String, List<String>) -> Unit
) {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    private var parentKey: String? = null
    private var order by mutableStateOf(emptyList<String>())
    private var top by mutableFloatStateOf(0f)
    private var height = 0
    private var lastDirection = 0f
    private var pendingIndex: Int? = null

    val rows: List<ShelfRowItem>
        get() {
            if(draggedKey == null) return sourceRows
            val volumes = sourceRows.filter { it.parent?.book?.ref?.key == parentKey }.associateBy { it.saved.book.ref.key }
            var index = 0
            return sourceRows.map { if(it.parent?.book?.ref?.key == parentKey) volumes.getValue(order[index++]) else it }
        }

    val offset: Float
        get() = draggedKey?.let { key -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { top - it.offset } } ?: 0f

    fun siblings(key: String): List<String> {
        val parent = sourceRows.firstOrNull { it.saved.book.ref.key == key }?.parent ?: return emptyList()
        return sourceRows.filter { it.parent?.book?.ref == parent.book.ref }.map { it.saved.book.ref.key }
    }

    fun start(key: String) {
        val row = sourceRows.firstOrNull { it.saved.book.ref.key == key } ?: return
        val parent = row.parent ?: return
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        order = siblings(key)
        if(order.size < 2) return
        parentKey = parent.book.ref.key
        top = info.offset.toFloat()
        height = info.size
        lastDirection = 0f
        pendingIndex = null
        draggedKey = key
    }

    fun drag(delta: Float) {
        if(draggedKey == null) return
        if(delta != 0f) lastDirection = delta
        val layout = list.layoutInfo
        top = (top + delta).coerceIn(layout.viewportStartOffset.toFloat(), maxOf(layout.viewportStartOffset.toFloat(), (layout.viewportEndOffset - height).toFloat()))
        moveAcrossSiblings()
    }

    private fun moveAcrossSiblings() {
        val key = draggedKey ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        // 等待列表更新条目位置后，再判断下一次跨越。
        if(pendingIndex != null && current.index != pendingIndex) return
        pendingIndex = null
        val center = top + height / 2f
        val from = order.indexOf(key)
        val target = visible.filter { it.key in order && it.key != key }.filter {
            val to = order.indexOf(it.key)
            if(lastDirection > 0) to > from && center > it.offset + it.size / 2f
            else lastDirection < 0 && to < from && center < it.offset + it.size / 2f
        }.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        val to = order.indexOf(target.key)
        // 首个可见项被重排时，保持视口位置稳定。
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        pendingIndex = target.index
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    suspend fun scrollAtEdge(edge: Float, maxStep: Float) {
        if(draggedKey == null) return
        val layout = list.layoutInfo
        val above = top - layout.viewportStartOffset
        val below = layout.viewportEndOffset - (top + height)
        val index = order.indexOf(draggedKey)
        val step = when {
            lastDirection < 0 && index > 0 && above < edge -> -maxStep * (1f - above / edge).coerceIn(0f, 1f)
            lastDirection > 0 && index < order.lastIndex && below < edge -> maxStep * (1f - below / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if(step != 0f) list.scrollBy(step)
        moveAcrossSiblings()
    }

    fun finish() {
        val key = draggedKey ?: return
        val parent = parentKey ?: return
        val result = order
        val changed = result != siblings(key)
        cancel()
        if(changed) onCommit(parent, result)
    }

    fun cancel() {
        draggedKey = null
        parentKey = null
        pendingIndex = null
        order = emptyList()
    }

    fun moveOne(key: String, delta: Int) {
        val row = sourceRows.firstOrNull { it.saved.book.ref.key == key } ?: return
        val parent = row.parent ?: return
        val keys = siblings(key)
        val from = keys.indexOf(key)
        val to = from + delta
        if(from >= 0 && to in keys.indices) onCommit(parent.book.ref.key, keys.toMutableList().apply { add(to, removeAt(from)) })
    }
}

@Composable internal fun rememberVolumeReorderState(
    list: LazyListState, rows: List<ShelfRowItem>, enabled: Boolean, onCommit: (String, List<String>) -> Unit
): VolumeReorderState {
    val commit by rememberUpdatedState(onCommit)
    val state = remember(list, rows, enabled) { VolumeReorderState(list, rows) { parent, keys -> commit(parent, keys) } }
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    LaunchedEffect(state, state.draggedKey, edge, speed) {
        if(state.draggedKey != null) {
            var lastFrame = withFrameNanos { it }
            while(state.draggedKey != null) {
                val frame = withFrameNanos { it }
                val scale = ((frame - lastFrame) / 16_666_667f).coerceIn(0f, 3f)
                lastFrame = frame
                state.scrollAtEdge(edge, speed * scale)
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

@Composable internal fun VolumeDragHandle(state: VolumeReorderState, key: String, title: String) {
    var menu by remember { mutableStateOf(false) }
    val siblings = state.siblings(key)
    val index = siblings.indexOf(key)
    if(LocalEInkMode.current) {
        Column {
            AppIconButton(onClick = { state.moveOne(key, -1) }, enabled = index > 0,
                modifier = Modifier.size(48.dp).testTag("volume-up-$key")) { Icon(Icons.Outlined.KeyboardArrowUp, "上移 $title") }
            AppIconButton(onClick = { state.moveOne(key, 1) }, enabled = index in 0 until siblings.lastIndex,
                modifier = Modifier.size(48.dp).testTag("volume-down-$key")) { Icon(Icons.Outlined.KeyboardArrowDown, "下移 $title") }
        }
        return
    }
    Box {
        AppIconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("volume-drag-$key")
            .pointerInput(state, key) {
                detectDragGestures(
                    onDragStart = { menu = false; state.start(key) },
                    onDragCancel = state::cancel,
                    onDragEnd = state::finish,
                    onDrag = { change, amount -> change.consume(); state.drag(amount.y) }
                )
            }) { Icon(Icons.Outlined.DragHandle, "拖动排序 $title") }
        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("上移一位") }, enabled = index > 0, onClick = { menu = false; state.moveOne(key, -1) })
            DropdownMenuItem(text = { Text("下移一位") }, enabled = index in 0 until siblings.lastIndex, onClick = { menu = false; state.moveOne(key, 1) })
        }
    }
}
