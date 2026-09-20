package cc.novelia.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

@Composable internal fun rememberReaderChapterOverscrollGesture(): ReaderChapterOverscrollGesture {
    // A short pull, independent of viewport height; the body follows with light resistance.
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(threshold) { ReaderChapterOverscrollGesture(threshold) }
}

/** Consume only the chapter-end remainder; ordinary scrolling and text selection stay with the list. */
@Composable internal fun Modifier.readerChapterOverscroll(
    state: LazyListState,
    gesture: ReaderChapterOverscrollGesture,
    enabled: Boolean,
    onNextChapter: () -> Unit
): Modifier {
    if(!enabled) return this
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val connection = remember(state, gesture) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // First put the pulled body back, then let any remaining downward drag scroll.
                if(source == NestedScrollSource.UserInput && available.y > 0f)
                    return Offset(0f, gesture.withdraw(available.y))
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if(source == NestedScrollSource.UserInput) {
                    // The list's native overscroll is disabled: it must not swallow this distance.
                    return Offset(0f, gesture.pull(available.y, !state.canScrollForward && state.layoutInfo.totalItemsCount > 0))
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if(gesture.suppressFling) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    return nestedScroll(connection).pointerInput(state, gesture) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.begin()
                if(down.type != PointerType.Touch || down.isConsumed || currentEvent.changes.size != 1) gesture.cancel()
                try {
                    while(true) {
                        // The Initial pass observes cancellation/extra fingers before the list handles them.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if(pointer == null) { gesture.cancel(); break }
                        if(pointer.isConsumed || event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) gesture.cancel()
                        if(!pointer.pressed) {
                            val released = pointer.previousPressed && !pointer.isConsumed
                            awaitPointerEvent(PointerEventPass.Final)
                            if(released && gesture.release() && !state.canScrollForward) latestNextChapter()
                            break
                        }
                    }
                } finally {
                    gesture.cancel()
                }
            }
        } finally {
            // Removing/ disabling the modifier or an OS gesture cancellation must never turn a chapter.
            gesture.cancel()
        }
    }
}

/** Only an uninterrupted, single-finger drag may commit the current chapter-end pull. */
internal class ReaderChapterOverscrollGesture(private val threshold: Float) {
    var active by mutableStateOf(false)
        private set
    var distance by mutableFloatStateOf(0f)
        private set
    var suppressFling = false
        private set

    val progress: Float get() = (distance / threshold).coerceIn(0f, 1f)
    val ready: Boolean get() = active && distance >= threshold
    val offset: Float get() = distance * .8f

    fun begin() { active = true; distance = 0f; suppressFling = false }

    fun pull(availableY: Float, atEnd: Boolean): Float {
        if(!active) return 0f
        if(!atEnd) { distance = 0f; suppressFling = false; return 0f }
        if(availableY >= 0f) return 0f
        distance = (distance - availableY).coerceAtMost(threshold * 2f)
        suppressFling = true
        return availableY
    }

    fun withdraw(availableY: Float): Float {
        if(!active || availableY <= 0f) return 0f
        val consumed = availableY.coerceAtMost(distance)
        distance -= consumed
        // The remainder goes back to normal reading; its eventual fling belongs to that scroll.
        if(availableY > consumed) suppressFling = false
        return consumed
    }

    fun cancel() { active = false; distance = 0f }

    fun release(): Boolean {
        val trigger = ready
        cancel()
        return trigger
    }
}
