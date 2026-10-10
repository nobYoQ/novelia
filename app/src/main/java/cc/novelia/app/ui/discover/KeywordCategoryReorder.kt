package cc.novelia.app.ui.discover

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

/** 拖动时只改草稿；松手保存一次，取消手势恢复原顺序。 */
internal class KeywordCategoryReorderState(
    private val list: LazyListState,
    private val categories: List<String>,
    private val onCommit: (List<String>) -> Unit,
) {
    var draggedName by mutableStateOf<String?>(null)
        private set
    var order by mutableStateOf(categories)
        private set
    private var top by mutableFloatStateOf(0f)
    private var height = 0
    private var direction = 0f
    private var pendingIndex: Int? = null

    val offset: Float
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedName }?.let { top - it.offset } ?: 0f

    fun start(name: String) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == name } ?: return
        if(categories.size < 2) return
        order = categories
        top = item.offset.toFloat()
        height = item.size
        direction = 0f
        pendingIndex = null
        draggedName = name
    }

    fun drag(delta: Float) {
        if(draggedName == null) return
        if(delta != 0f) direction = delta
        val layout = list.layoutInfo
        top = (top + delta).coerceIn(layout.viewportStartOffset.toFloat(),
            maxOf(layout.viewportStartOffset.toFloat(), (layout.viewportEndOffset - height).toFloat()))
        moveAcrossRows()
    }

    private fun moveAcrossRows() {
        val name = draggedName ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == name } ?: return
        if(pendingIndex != null && current.index != pendingIndex) return
        pendingIndex = null
        val center = top + height / 2f
        val from = order.indexOf(name)
        val target = visible.filter { it.key in order && it.key != name }.filter {
            val to = order.indexOf(it.key)
            if(direction > 0) to > from && center > it.offset + it.size / 2f
            else direction < 0 && to < from && center < it.offset + it.size / 2f
        }.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        pendingIndex = target.index
        order = order.toMutableList().apply { add(order.indexOf(target.key), removeAt(from)) }
    }

    suspend fun scrollAtEdge(edge: Float, maxStep: Float) {
        val name = draggedName ?: return
        val layout = list.layoutInfo
        val above = top - layout.viewportStartOffset
        val below = layout.viewportEndOffset - (top + height)
        val index = order.indexOf(name)
        val step = when {
            direction < 0 && index > 0 && above < edge -> -maxStep * (1f - above / edge).coerceIn(0f, 1f)
            direction > 0 && index < order.lastIndex && below < edge -> maxStep * (1f - below / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if(step != 0f) list.scrollBy(step)
        moveAcrossRows()
    }

    fun finish() {
        if(draggedName == null) return
        val result = order
        cancel()
        if(result != categories) onCommit(result)
    }

    fun cancel() {
        draggedName = null
        pendingIndex = null
        order = categories
    }

    fun moveOne(name: String, delta: Int) {
        val from = categories.indexOf(name)
        val to = from + delta
        if(from >= 0 && to in categories.indices) onCommit(categories.toMutableList().apply { add(to, removeAt(from)) })
    }
}

@Composable internal fun rememberKeywordCategoryReorderState(
    list: LazyListState, categories: List<String>, onCommit: (List<String>) -> Unit,
): KeywordCategoryReorderState {
    val commit by rememberUpdatedState(onCommit)
    val state = remember(list, categories) { KeywordCategoryReorderState(list, categories) { commit(it) } }
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    LaunchedEffect(state, state.draggedName, edge, speed) {
        if(state.draggedName != null) {
            var lastFrame = withFrameNanos { it }
            while(state.draggedName != null) {
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

@Composable internal fun KeywordCategoryDragHandle(state: KeywordCategoryReorderState, name: String) {
    val index = state.order.indexOf(name)
    if(LocalEInkMode.current) {
        Column {
            AppIconButton(onClick = { state.moveOne(name, -1) }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, "上移分类 $name")
            }
            AppIconButton(onClick = { state.moveOne(name, 1) }, enabled = index in 0 until state.order.lastIndex, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, "下移分类 $name")
            }
        }
        return
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("keyword-category-drag-$name")
            .pointerInput(state, name) {
                detectDragGestures(onDragStart = { menu = false; state.start(name) }, onDragCancel = state::cancel,
                    onDragEnd = state::finish, onDrag = { change, amount -> change.consume(); state.drag(amount.y) })
            }) { Icon(Icons.Outlined.DragHandle, "拖动排序分类 $name") }
        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("上移一位") }, enabled = index > 0, onClick = { menu = false; state.moveOne(name, -1) })
            DropdownMenuItem(text = { Text("下移一位") }, enabled = index in 0 until state.order.lastIndex, onClick = { menu = false; state.moveOne(name, 1) })
        }
    }
}
