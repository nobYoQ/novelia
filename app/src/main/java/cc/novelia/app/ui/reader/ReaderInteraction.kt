package cc.novelia.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** 只观察正文触摸，不消费事件；原有翻页、长按选文及控件继续处理同一手势。 */
@Composable internal fun readerInteractionModifier(onInteraction: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onInteraction)
    return Modifier.pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); latest() }
    }
}
