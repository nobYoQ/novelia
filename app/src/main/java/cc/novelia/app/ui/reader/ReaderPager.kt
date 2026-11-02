package cc.novelia.app.ui.reader

import cc.novelia.app.reader.StaticPage

/**
 * 阅读器翻页实现的统一门面。
 *
 * 官方实现（[EInkPageState]，预切页表）与行带实现（[RowPagingState]）都实现它，
 * 让 [ReaderScreen] 只依赖这组成员，不必区分具体是哪种翻页模型。
 * 这样上游改动阅读器时，冲突只会落在各自的实现文件里，不会互相覆盖。
 */
internal interface ReaderPager {
    val pages: List<StaticPage>
    val pageIndex: Int
    val ready: Boolean

    /** 视口首行所属的段落下标。 */
    val paragraph: Int

    /** 视口首行的字符偏移（保存阅读位置用）。 */
    val textOffset: Int

    /** 章节内原始段落下标，供跨模式恢复阅读位置。 */
    val sourceIndex: Int?

    /** 请求的字符锚点，重排和模式切换不能用临时页首覆盖它。 */
    val anchor: ReadingRestoreAnchor

    val canGoBack: Boolean
    val canGoForward: Boolean

    fun move(direction: Int)
    /** 进度条的绝对定位，不应被解释成从当前行连续翻多屏。 */
    fun seekPage(index: Int) { move(index - pageIndex) }
    fun invalidate()

    /** 定位到指定段落/字符偏移，按需记录来源段落下标。 */
    fun find(paragraph: Int, offset: Int = 0, source: Int? = null)
}
