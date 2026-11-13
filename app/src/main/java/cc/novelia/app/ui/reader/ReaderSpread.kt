package cc.novelia.app.ui.reader

import cc.novelia.app.data.model.ReaderSettings

/** 双页只属于横向分页；判定使用避开系统安全区后的窗口宽度。 */
internal fun readerUsesDualPage(settings: ReaderSettings, availableWidthDp: Float): Boolean =
    settings.paginationMode == "auto" && settings.horizontalDualPage && availableWidthDp >= 720f

/** 进度定位、恢复和末页都落到同一跨页起点，最后可保留一张空白右页。 */
internal fun spreadStart(page: Int, pageCount: Int, columns: Int): Int {
    val size = columns.coerceIn(1, 2)
    return page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) / size * size
}
