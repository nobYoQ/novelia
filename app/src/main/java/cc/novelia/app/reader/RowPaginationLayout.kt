package cc.novelia.app.reader

/** 行带的纯几何模型。页码仅用于进度展示，阅读和翻页始终使用实际行起点。 */
internal class RowPaginationLayout(val lines: List<PageLine>, val height: Int, val gap: Int) {
    init { require(height > 0 && gap >= 0) }
    private val tops = FloatArray(lines.size)
    val totalHeight: Float
    val pageStarts: List<Int>
    val pages: List<StaticPage>
    val maxScrollTop: Float

    init {
        var y = 0f
        lines.forEachIndexed { i, line ->
            require(line.height > 0)
            if(i > 0 && line.paragraph != lines[i - 1].paragraph) y += gap
            tops[i] = y
            y += line.height.coerceAtMost(height)
        }
        totalHeight = y
        val starts = mutableListOf<Int>()
        var row = 0
        while(row < lines.size) { starts += row; row = screenEnd(row) }
        pageStarts = starts
        pages = starts.map { StaticPage(lines.subList(it, screenEnd(it))) }.ifEmpty { listOf(StaticPage(emptyList())) }
        maxScrollTop = maxOf((totalHeight - height).coerceAtLeast(0f), top(starts.lastOrNull() ?: 0))
    }

    fun top(row: Int): Float = tops.getOrNull(row) ?: if(row >= lines.size) totalHeight else 0f

    fun rowAt(y: Float): Int {
        if(lines.isEmpty()) return 0
        var low = 0
        var high = lines.lastIndex
        while(low < high) {
            val mid = (low + high + 1) / 2
            if(tops[mid] <= y + .01f) low = mid else high = mid - 1
        }
        return low
    }

    /** 每屏只收完整行；插图单独占屏，超大单行由渲染层缩放。 */
    fun screenEnd(from: Int): Int {
        if(from !in lines.indices) return lines.size
        if(lines[from].image) return from + 1
        var end = from
        var used = 0
        while(end < lines.size) {
            val line = lines[end]
            if(line.image) break
            val spacing = if(end > from && line.paragraph != lines[end - 1].paragraph) gap else 0
            val size = line.height.coerceAtMost(height)
            if(used + spacing + size > height) break
            used += spacing + size
            end++
        }
        return end.coerceAtLeast(from + 1)
    }

    fun previousStart(from: Int): Int {
        var start = from
        var used = 0
        while(start > 0) {
            val line = lines[start - 1]
            if(line.image) return if(start == from) start - 1 else start
            val spacing = if(start < from && line.paragraph != lines[start].paragraph) gap else 0
            val size = line.height.coerceAtMost(height)
            if(used + spacing + size > height) break
            used += spacing + size
            start--
        }
        return if(start == from) (from - 1).coerceAtLeast(0) else start
    }

    fun pageAt(row: Int): Int = pageStarts.binarySearch(row).let { if(it >= 0) it else (-it - 2).coerceAtLeast(0) }

    fun rowFor(paragraph: Int, offset: Int): Int {
        if(lines.isEmpty()) return 0
        val exact = lines.indexOfFirst { it.paragraph == paragraph && (it.image || offset >= it.start && offset < it.end) }
        if(exact >= 0) return exact
        return lines.indexOfLast { it.paragraph <= paragraph }.coerceAtLeast(0)
    }

    fun nearestRow(top: Float): Int {
        val row = rowAt(top)
        return if(row < lines.lastIndex && top - this.top(row) > this.top(row + 1) - top) row + 1 else row
    }

    /** 显示切片可以包含首尾局部行；固定页表仍以完整行统计翻页起点。 */
    fun slice(top: Float, includePartialLines: Boolean): StaticPage {
        if(lines.isEmpty()) return StaticPage(emptyList())
        val from = rowAt(top)
        var end = screenEnd(from)
        if(includePartialLines) {
            end = from + 1
            while(end < lines.size && this.top(end) < top + height) end++
        }
        return StaticPage(lines.subList(from, end))
    }
}
