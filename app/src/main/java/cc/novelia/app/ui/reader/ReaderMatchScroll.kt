package cc.novelia.app.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import cc.novelia.app.reader.ParagraphScrollLayout
import kotlinx.coroutines.flow.first

/** 已测量的句子直接滚到目标行；未挂载的段落先平滑进入视口，再补齐精确位置。 */
internal suspend fun LazyListState.revealReadingLine(
    index: Int, textOffset: Int, partCount: Int, animate: Boolean, overlay: Int,
    layout: () -> ParagraphScrollLayout?,
) {
    // 首次挂载也保留同样的上文空间，避免先越过目标、再反向回滚。
    val contextInset = if(animate) (layoutInfo.viewportSize.height * .22f).toInt() else 0
    val inset = maxOf(overlay, contextInset)
    if(layout()?.parts?.size != partCount) {
        if(animate) animateScrollToItem(index, -inset) else scrollToItem(index)
    }
    val measured = snapshotFlow(layout).first { it != null && it.parts.size == partCount }!!
    // 留出上文，让连续切句时视线稳定在屏幕上部。
    val offset = measured.scrollOffsetAt(textOffset) - inset
    if(animate) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}
