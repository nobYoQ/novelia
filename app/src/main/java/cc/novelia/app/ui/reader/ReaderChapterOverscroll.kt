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
    // 上拉阈值不随视口高度变化，正文带轻微阻力跟随手指。
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(threshold) { ReaderChapterOverscrollGesture(threshold) }
}

/** 只消费章末无法继续滚动的余量，普通滚动和文字选择仍交给列表。 */
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
                // 先收回已上拉的正文，再把剩余向下拖动交给正常滚动。
                if(source == NestedScrollSource.UserInput && available.y > 0f)
                    return Offset(0f, gesture.withdraw(available.y))
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if(source == NestedScrollSource.UserInput) {
                    // 关闭列表原生越界效果，避免它吞掉这里用于章末上拉的距离。
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
                        // 在 Initial 阶段先观察取消和多指输入，再由列表处理事件。
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
            // 修饰符移除、禁用或系统手势取消时，均不得切换章节。
            gesture.cancel()
        }
    }
}

/**
 * 只有未中断的单指拖动，才能提交章末上拉；distance/threshold 为正向像素距离，
 * pull 接收嵌套滚动剩余位移，向上为负。正文跟随距离带阻力，上拉累计最多两倍阈值。
 * cancel 清理可视状态，但保留本次抑制惯性的标记，避免松手后的惯性继续传给弹层；
 * 下一次 begin 或向下拖回普通滚动区时才解除抑制。
 */
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
        // 剩余位移交还普通阅读滚动，之后的惯性也由该滚动处理。
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
