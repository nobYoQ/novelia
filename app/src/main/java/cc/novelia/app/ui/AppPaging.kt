package cc.novelia.app.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.pageDown
import androidx.compose.ui.semantics.pageUp
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keep a little of the previous screen in view, including lines or cards split at the edge. */
internal fun screenPageDistance(viewport: Int, overlap: Float): Float =
    (viewport - minOf(overlap, viewport * .2f)).coerceAtLeast(0f)

@Composable internal fun AppLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    listModifier: Modifier = Modifier,
    onPageTurn: (Int) -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val eInk = LocalEInkMode.current
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
            modifier = listModifier.weight(1f).screenPageInput(eInk, page = page,
                scroll = { amount -> scope.launch { state.scrollBy(amount) }; Unit }), content = content)
        if(eInk && (state.canScrollBackward || state.canScrollForward)) ScreenPageButtons(state.canScrollBackward, state.canScrollForward, page)
    }
}

@Composable internal fun AppScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val eInk = LocalEInkMode.current
    val scope = rememberCoroutineScope()
    val overlap = with(LocalDensity.current) { 48.dp.toPx() }
    val page: (Int) -> Unit = { direction -> scope.launch { state.scrollBy(direction * screenPageDistance(state.viewportSize, overlap)) }; Unit }
    Column(modifier) {
        Column(Modifier.weight(1f, fill = false).appVerticalScroll(state).then(contentModifier),
            verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment, content = content)
        if(eInk && (state.canScrollBackward || state.canScrollForward)) ScreenPageButtons(state.canScrollBackward, state.canScrollForward, page)
    }
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
                TextButton(onClick = { page(-1) }, enabled = back) { Text("上一屏") }
                TextButton(onClick = { page(1) }, enabled = forward) { Text("下一屏") }
            }
        }
    }
}

/** Consume movement without changing the viewport. Commit at most one page when the finger lifts. */
@Composable private fun Modifier.screenPageInput(enabled: Boolean, horizontal: Boolean = false, page: (Int) -> Unit, scroll: (Float) -> Unit): Modifier {
    if(!enabled) return this
    val latestPage by rememberUpdatedState(page)
    val latestScroll by rememberUpdatedState(scroll)
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return this
        .semantics {
            scrollBy { x, y ->
                val amount = if(horizontal) x else y
                // Focus/accessibility requests may expose only part of a field; jump exactly to it.
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
