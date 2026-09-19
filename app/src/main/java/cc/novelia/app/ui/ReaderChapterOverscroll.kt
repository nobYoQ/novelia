package cc.novelia.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Observe the list's unconsumed drag without taking over normal scrolling or text selection. */
@Composable internal fun Modifier.readerChapterOverscroll(
    state: LazyListState,
    enabled: Boolean,
    onNextChapter: () -> Unit
): Modifier {
    if(!enabled) return this
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val gesture = remember(state, threshold) { ReaderChapterOverscrollGesture(threshold) }
    val connection = remember(state, gesture) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Withdrawing the upward pull cancels it even if the list consumes the return drag.
                if(source == NestedScrollSource.UserInput && available.y > 0f) gesture.cancel()
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if(source == NestedScrollSource.UserInput) {
                    // This remainder is reported before Android's edge effect applies its stretch.
                    // Neither scrolling to the bottom nor a fling counts as pulling past the end.
                    gesture.pull(available.y, !state.canScrollForward && state.layoutInfo.totalItemsCount > 0)
                }
                return Offset.Zero
            }
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
                        // Android's stretched bottom edge may consume the entire return movement
                        // before nested scroll sees it, so observe the finger itself as well.
                        if(pointer.position.y > pointer.previousPosition.y) gesture.cancel()
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

/** Only an uninterrupted, single-finger drag may commit the accumulated chapter-end remainder. */
internal class ReaderChapterOverscrollGesture(private val threshold: Float) {
    private var active = false
    private var distance = 0f

    fun begin() { active = true; distance = 0f }

    fun pull(availableY: Float, atEnd: Boolean) {
        if(!active) return
        if(!atEnd) { distance = 0f; return }
        if(availableY < 0f) distance -= availableY
    }

    fun cancel() { active = false; distance = 0f }

    fun release(): Boolean {
        val trigger = active && distance >= threshold
        cancel()
        return trigger
    }
}
