package cc.novelia.app.ui.reader

import androidx.compose.ui.unit.IntRect
import cc.novelia.app.data.model.ReaderSettings

/** 双页只属于横向分页；判定使用避开系统安全区后的窗口宽度。 */
internal fun readerUsesDualPage(settings: ReaderSettings, availableWidthDp: Float): Boolean =
    settings.paginationMode == "auto" && settings.horizontalDualPage && availableWidthDp >= 720f

internal data class ReaderSpreadLayout(val leftPageX: Int, val rightPageX: Int, val pageWidth: Int, val dividerX: Int?)

/** 页框以正文视口为原点，折痕以窗口为原点。两页共用较窄一侧的可排版宽度。 */
internal fun readerSpreadLayout(
    viewport: IntRect, verticalFolds: List<IntRect>, maxPageWidth: Int, gutterWidth: Int, pageInset: Int,
): ReaderSpreadLayout {
    val width = viewport.width
    val fold = verticalFolds.firstOrNull {
        it.left > viewport.left && it.right < viewport.right && it.top < viewport.bottom && it.bottom > viewport.top
    }
    val gutter = gutterWidth.coerceAtMost((width - 2).coerceAtLeast(0))
    val extra = if(fold == null) 0 else (gutter - fold.width).coerceAtLeast(0)
    val gutterStart = if(fold == null) (width - gutter) / 2 else (fold.left - viewport.left - extra / 2).coerceAtLeast(1)
    val gutterEnd = if(fold == null) gutterStart + gutter else (fold.right - viewport.left + extra - extra / 2).coerceAtMost(width - 1)

    val rightPanelWidth = width - gutterEnd
    val frameWidth = minOf(gutterStart, rightPanelWidth, maxPageWidth).coerceAtLeast(1)
    val inset = pageInset.coerceAtMost((frameWidth - 1) / 2)
    return ReaderSpreadLayout(
        leftPageX = (gutterStart - frameWidth) / 2 + inset,
        rightPageX = gutterEnd + (rightPanelWidth - frameWidth) / 2 + inset,
        pageWidth = frameWidth - 2 * inset,
        dividerX = if(fold == null) gutterStart + gutter / 2 else null,
    )
}

/** 进度定位、恢复和末页都落到同一跨页起点，最后可保留一张空白右页。 */
internal fun spreadStart(page: Int, pageCount: Int, columns: Int): Int {
    val size = columns.coerceIn(1, 2)
    return page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) / size * size
}
