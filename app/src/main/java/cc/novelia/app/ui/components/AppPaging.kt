package cc.novelia.app.ui.components

import cc.novelia.app.ui.components.AppTextButton

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.pageDown
import androidx.compose.ui.semantics.pageUp
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalScreenPageButtons
import kotlin.math.abs
import kotlinx.coroutines.launch

/** 翻屏时保留少量上一屏内容，防止跳过边缘被截开的文字行或卡片。 */
internal fun screenPageDistance(viewport: Int, overlap: Float): Float =
    (viewport - minOf(overlap, viewport * .2f)).coerceAtLeast(0f)

/**
 * 应用列表统一入口：普通模式使用原生滚动，电子纸模式关闭连续拖动，改为按屏跳转。
 * 每屏保留少量重叠内容，避免边缘半行/半张卡片被跳过；正文阅读器的预先分页由 EInkPage 处理。
 * onPageTurn 可让页面在手动翻屏时处理附加状态，例如收起筛选区。
 */
@Composable internal fun AppLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberContentLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    listModifier: Modifier = Modifier,
    onPageTurn: (Int) -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val eInk = LocalEInkMode.current
    val inSheet = LocalInAppSheet.current
    val sheetEnd = remember(inSheet, state) { if (inSheet) SheetEndOverscrollConnection { state.canScrollForward } else null }
    val scope = rememberCoroutineScope()
    val overlap = with(LocalDensity.current) { 48.dp.toPx() }
    val page: (Int) -> Unit = { direction -> onPageTurn(direction); scope.launch {
        val layout = state.layoutInfo
        state.scrollBy(direction * screenPageDistance(layout.viewportEndOffset - layout.viewportStartOffset, overlap))
    }; Unit }
    LaunchedEffect(eInk) { if(eInk) state.stopScroll() }
    Column(modifier) {
        LazyColumn(state = state, contentPadding = contentPadding, verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment, userScrollEnabled = !eInk,
            modifier = listModifier.weight(1f).then(if (sheetEnd != null) Modifier.nestedScroll(sheetEnd) else Modifier).screenPageInput(eInk, page = page,
                scroll = { amount -> scope.launch { state.scrollBy(amount) }; Unit }), content = content)
        if(eInk && LocalScreenPageButtons.current && (state.canScrollBackward || state.canScrollForward)) ScreenPageButtons(state.canScrollBackward, state.canScrollForward, page)
    }
}

@Composable internal fun AppScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberContentScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val eInk = LocalEInkMode.current
    val inSheet = LocalInAppSheet.current
    val sheetEnd = remember(inSheet, state) { if (inSheet) SheetEndOverscrollConnection { state.canScrollForward } else null }
    val scope = rememberCoroutineScope()
    val overlap = with(LocalDensity.current) { 48.dp.toPx() }
    val page: (Int) -> Unit = { direction -> scope.launch { state.scrollBy(direction * screenPageDistance(state.viewportSize, overlap)) }; Unit }
    Column(modifier) {
        Column(Modifier.weight(1f, fill = false).then(if (sheetEnd != null) Modifier.nestedScroll(sheetEnd) else Modifier)
            .appVerticalScroll(state).then(contentModifier),
            verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment, content = content)
        if(eInk && LocalScreenPageButtons.current && (state.canScrollBackward || state.canScrollForward)) ScreenPageButtons(state.canScrollBackward, state.canScrollForward, page)
    }
}

/** 列表到末尾后的向上惯性留在列表内，向下运动仍可关闭底部弹层。 */
internal class SheetEndOverscrollConnection(private val canScrollForward: () -> Boolean) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f && !canScrollForward()) Offset(0f, available.y)
        else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f && !canScrollForward()) Velocity(0f, available.y) else Velocity.Zero
}

@Composable internal fun Modifier.appVerticalScroll(state: ScrollState): Modifier = appScroll(state, false)
@Composable internal fun Modifier.appHorizontalScroll(state: ScrollState): Modifier = appScroll(state, true)

@Composable private fun Modifier.appScroll(state: ScrollState, horizontal: Boolean): Modifier {
    val eInk = LocalEInkMode.current
    val scope = rememberCoroutineScope()
    val overlap = with(LocalDensity.current) { 48.dp.toPx() }
    val page: (Int) -> Unit = { direction -> scope.launch { state.scrollBy(direction * screenPageDistance(state.viewportSize, overlap)) }; Unit }
    LaunchedEffect(eInk) { if(eInk) state.stopScroll() }
    val input = screenPageInput(eInk, horizontal, page) { amount -> scope.launch { state.scrollBy(amount) }; Unit }
    return if(horizontal) input.horizontalScroll(state, enabled = !eInk) else input.verticalScroll(state, enabled = !eInk)
}

@Composable internal fun ScreenPageButtons(back: Boolean, forward: Boolean, page: (Int) -> Unit) {
    Surface {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                AppTextButton(onClick = { page(-1) }, enabled = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一屏") }
                AppTextButton(onClick = { page(1) }, enabled = forward, modifier = Modifier.heightIn(min = 48.dp)) { Text("下一屏") }
            }
        }
    }
}

/**
 * 电子纸输入适配：拖动期间只累计距离，松手达到阈值才翻一屏，取消手势不产生翻页。
 * 同时适配翻页键、限频滚轮和可访问性请求；可访问性精确滚动保留原距离，避免焦点目标被跳过。
 * rememberUpdatedState 保证持续存在的手势协程调用最新页面回调。
 */
@Composable private fun Modifier.screenPageInput(enabled: Boolean, horizontal: Boolean = false, page: (Int) -> Unit, scroll: (Float) -> Unit): Modifier {
    if(!enabled) return this
    val latestPage by rememberUpdatedState(page)
    val latestScroll by rememberUpdatedState(scroll)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return this
        .semantics {
            scrollBy { x, y ->
                val amount = if(horizontal) x else y
                // 焦点或无障碍请求可能只显露输入框的一部分，应精确跳到请求位置。
                if(amount != 0f) latestScroll(amount)
                amount != 0f
            }
            if(!horizontal) {
                pageUp { latestPage(-1); true }
                pageDown { latestPage(1); true }
            }
        }
        .onPreviewKeyEvent {
            if(it.type == KeyEventType.KeyUp && it.key in listOf(Key.PageUp, Key.PageDown)) {
                latestPage(if(it.key == Key.PageDown) 1 else -1); true
            } else it.key in listOf(Key.PageUp, Key.PageDown)
        }
        .pointerInput(horizontal, threshold) {
            var distance = 0f
            val end = { if(abs(distance) >= threshold) latestPage(if(distance < 0) 1 else -1); distance = 0f }
            if(horizontal) detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = end) { change, amount ->
                change.consume(); distance += amount
            } else detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = end) { change, amount ->
                change.consume(); distance += amount
            }
        }
        .pointerInput(horizontal) {
            awaitPointerEventScope {
                var lastWheel = Long.MIN_VALUE
                while(true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if(event.type == PointerEventType.Scroll) {
                        val delta = event.changes.sumOf { (if(horizontal) it.scrollDelta.x else it.scrollDelta.y).toDouble() }
                        val time = event.changes.firstOrNull()?.uptimeMillis ?: continue
                        if(delta != 0.0 && (lastWheel == Long.MIN_VALUE || time - lastWheel > 180)) {
                            latestPage(if(delta > 0) 1 else -1); lastWheel = time
                        }
                        if(delta != 0.0) event.changes.forEach { it.consume() }
                    }
                }
            }
        }
}
