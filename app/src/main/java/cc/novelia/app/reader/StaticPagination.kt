package cc.novelia.app.reader

/** 已测量且不再拆分的一行或一张插图；height 为像素，start/end 为整段显示文本的字符区间。 */
data class PageLine(val paragraph: Int, val start: Int, val end: Int, val height: Int, val image: Boolean = false)
data class StaticPage(val lines: List<PageLine>)

/**
 * 将已测量的行装入固定高度页面，段距只加在同页的不同段落之间，插图独占一页。
 * keepTogetherParagraphs 中能完整放入一页的段落优先整体换页，避免短双语段落跨页分离；
 * 超过一页的长段落仍按完整行拆分。单行高于页面时保留整行，交给显示层处理。
 * 即使输入为空也返回一张空页，保持阅读器页码和翻页状态具有有效的初始值。
 */
fun paginateLines(lines: List<PageLine>, height: Int, paragraphGap: Int, keepTogetherParagraphs: Set<Int> = emptySet(), checkCancelled: () -> Unit = {}): List<StaticPage> {
    require(height > 0)
    val pages = mutableListOf<StaticPage>()
    var page = mutableListOf<PageLine>()
    var used = 0
    val paragraphHeights = mutableMapOf<Int, Long>()
    if(keepTogetherParagraphs.isNotEmpty()) for(line in lines) {
        checkCancelled()
        if(line.paragraph in keepTogetherParagraphs && !line.image)
            paragraphHeights[line.paragraph] = (paragraphHeights[line.paragraph] ?: 0L) + line.height
    }
    fun flush() {
        if (page.isNotEmpty()) pages += StaticPage(page.toList())
        page = mutableListOf()
        used = 0
    }
    for (line in lines) {
        checkCancelled()
        require(line.height > 0 && line.end >= line.start)
        if (line.image) { flush(); pages += StaticPage(listOf(line)); continue }
        val gap = if (page.isNotEmpty() && page.last().paragraph != line.paragraph) paragraphGap.coerceAtLeast(0) else 0
        val groupHeight = paragraphHeights[line.paragraph]
        if(page.isNotEmpty() && page.last().paragraph != line.paragraph && groupHeight != null && groupHeight <= height && used + gap + groupHeight > height) flush()
        if (page.isNotEmpty() && used + gap + line.height > height) flush()
        if (page.isNotEmpty()) used += gap
        page += line
        used += line.height
    }
    flush()
    return pages.ifEmpty { listOf(StaticPage(emptyList())) }
}

/** 先按段落和字符范围精确定位；字体或视口变化使旧偏移失配时，退回不晚于目标段落的页。 */
fun pageForAnchor(pages: List<StaticPage>, paragraph: Int, offset: Int): Int {
    val exact = pages.indexOfFirst { page -> page.lines.any { it.paragraph == paragraph && offset >= it.start && (offset < it.end || it.image) } }
    if (exact >= 0) return exact
    return pages.indexOfLast { page -> page.lines.firstOrNull()?.let { it.paragraph <= paragraph } == true }.coerceAtLeast(0)
}

/** 保留纯函数形式的 Android 按键映射，便于回归验证遥控器和翻页器。 */
fun readerKeyDirection(keyCode: Int, volumeKeys: Boolean): Int = when (keyCode) {
    92, 19, 21 -> -1 // 上一页键、方向键上/左。
    93, 20, 22, 62 -> 1 // 下一页键、方向键下/右、空格键。
    24 -> if (volumeKeys) -1 else 0
    25 -> if (volumeKeys) 1 else 0
    else -> 0
}
