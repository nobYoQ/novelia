package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import cc.novelia.app.data.model.Position
import cc.novelia.app.reader.*

/** 唯一位置源是行带游标；页码、字符锚点和绘制都从这个位置导出。 */
@Stable internal class RowPagingState(initial: Position?) : ReaderPager {
    var layout by mutableStateOf<RowPaginationLayout?>(null)
        private set
    var viewportTop by mutableFloatStateOf(0f)
        private set
    var scrolling by mutableStateOf(false)
        private set
    override var ready by mutableStateOf(false)
        private set
    var navigationRevision by mutableIntStateOf(0)
        private set
    var transitionFrom: Float? = null
        private set
    private var requested by mutableStateOf(ReadingRestoreAnchor((initial?.index ?: 0) - 1, initial?.sourceParagraph, initial?.textOffset ?: 0))
    private var content = emptyList<ReadingParagraph>()
    private var measuredPages: List<StaticPage>? = null
    private val history = mutableListOf<Int>()
    override val pages get() = layout?.pages.orEmpty()
    val row get() = layout?.rowAt(viewportTop) ?: 0
    override val pageIndex get() = layout?.pageAt(row) ?: 0
    override val paragraph get() = layout?.lines?.getOrNull(row)?.paragraph ?: requested.paragraph
    override val textOffset get() = layout?.lines?.getOrNull(row)?.start ?: requested.textOffset
    override val sourceIndex get() = content.getOrNull(paragraph)?.index
    override val anchor get() = requested
    override val canGoBack get() = row > 0
    override val canGoForward get() = layout?.let { it.screenEnd(row) < it.lines.size } ?: false

    fun install(next: List<StaticPage>, paragraphs: List<ReadingParagraph>, height: Int, gap: Int) {
        val previous = content.getOrNull(requested.paragraph)
        val paragraph = requested.sourceIndex?.let { source ->
            paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } ?: paragraphs.lastIndex.coerceAtLeast(0)
        } ?: requested.paragraph
        val offset = if(previous != null && previous.parts != paragraphs.getOrNull(paragraph)?.parts) 0 else requested.textOffset
        content = paragraphs
        requested = ReadingRestoreAnchor(paragraph, paragraphs.getOrNull(paragraph)?.index, offset)
        val geometry = RowPaginationLayout(next.flatMap { it.lines }, height, gap)
        layout = geometry
        measuredPages = next
        val target = if(paragraph > (geometry.lines.lastOrNull()?.paragraph ?: 0)) geometry.pageStarts.lastOrNull() ?: 0
            else geometry.rowFor(paragraph, offset)
        viewportTop = geometry.top(target)
        if(geometry.lines.none { it.paragraph == paragraph }) captureAnchor()
        scrolling = false
        history.clear()
        transitionFrom = null
        navigationRevision++
        ready = true
    }

    fun isMeasured(next: List<StaticPage>) = measuredPages === next
    fun confirm(next: List<StaticPage>) { if(isMeasured(next)) ready = true }
    override fun invalidate() { ready = false }

    private fun captureAnchor() { requested = ReadingRestoreAnchor(paragraph, sourceIndex, textOffset) }

    fun targetRow(direction: Int): Int? {
        val geometry = layout ?: return null
        return if(direction > 0) geometry.screenEnd(row).takeIf { it < geometry.lines.size }
        else if(row == 0) null else history.lastOrNull { it < row } ?: geometry.previousStart(row)
    }

    override fun move(direction: Int) {
        if(!ready || direction == 0) return
        val geometry = layout ?: return
        val from = viewportTop
        repeat(kotlin.math.abs(direction).coerceAtMost(geometry.lines.size)) {
            val target = targetRow(direction) ?: return@repeat
            if(direction > 0) { if(history.lastOrNull() != row) history += row }
            else history.removeAll { it >= target }
            viewportTop = geometry.top(target)
        }
        scrolling = false
        captureAnchor()
        transitionFrom = from.takeIf { it != viewportTop && kotlin.math.abs(direction) == 1 }
        navigationRevision++
    }

    override fun seekPage(index: Int) {
        val geometry = layout ?: return
        if(!ready) return
        history.clear()
        viewportTop = geometry.top(geometry.pageStarts.getOrNull(index.coerceIn(0, pages.lastIndex.coerceAtLeast(0))) ?: 0)
        scrolling = false
        transitionFrom = null
        captureAnchor()
        navigationRevision++
    }

    override fun find(paragraph: Int, offset: Int, source: Int?) {
        requested = ReadingRestoreAnchor(paragraph, source ?: content.getOrNull(paragraph)?.index, offset.coerceAtLeast(0))
        layout?.let { viewportTop = it.top(it.rowFor(paragraph, offset)) }
        history.clear()
        scrolling = false
        transitionFrom = null
        navigationRevision++
    }

    fun beginScroll() { history.clear(); scrolling = true; transitionFrom = null }
    fun scrollBy(delta: Float): Float {
        val geometry = layout ?: return 0f
        if(!ready || !scrolling) return 0f
        val before = viewportTop
        // 横向末屏可以短于一屏，开始纵滑时不能把它突然拉回上一屏。
        viewportTop = (before + delta).coerceIn(0f, maxOf(before, geometry.maxScrollTop))
        captureAnchor()
        return viewportTop - before
    }
    fun finishScroll() {
        val geometry = layout ?: return
        viewportTop = geometry.top(geometry.nearestRow(viewportTop))
        scrolling = false
        captureAnchor()
    }

    val scrollTarget get() = layout?.let { it.top(it.nearestRow(viewportTop)) } ?: viewportTop

    /** 吸附动画沿同一像素游标推进；外部定位已结束滚动时，旧动画不能覆盖新位置。 */
    fun settleScroll(top: Float) {
        val geometry = layout ?: return
        if(!ready || !scrolling) return
        viewportTop = top.coerceIn(0f, geometry.top(geometry.lines.lastIndex.coerceAtLeast(0)))
        captureAnchor()
    }
}
