package cc.novelia.app.reader

/** A measured, indivisible line (or an image). Offsets refer to its paragraph text. */
data class PageLine(val paragraph: Int, val start: Int, val end: Int, val height: Int, val image: Boolean = false)
data class StaticPage(val lines: List<PageLine>)

/** Pack complete lines only. Images occupy their own page, scaled by the renderer. */
fun paginateLines(lines: List<PageLine>, height: Int, paragraphGap: Int): List<StaticPage> {
    require(height > 0)
    val pages = mutableListOf<StaticPage>()
    var page = mutableListOf<PageLine>()
    var used = 0
    fun flush() {
        if (page.isNotEmpty()) pages += StaticPage(page.toList())
        page = mutableListOf()
        used = 0
    }
    for (line in lines) {
        require(line.height > 0 && line.end >= line.start)
        if (line.image) { flush(); pages += StaticPage(listOf(line)); continue }
        val gap = if (page.isNotEmpty() && page.last().paragraph != line.paragraph) paragraphGap.coerceAtLeast(0) else 0
        if (page.isNotEmpty() && used + gap + line.height > height) flush()
        if (page.isNotEmpty()) used += gap
        page += line
        used += line.height
    }
    flush()
    return pages.ifEmpty { listOf(StaticPage(emptyList())) }
}

fun pageForAnchor(pages: List<StaticPage>, paragraph: Int, offset: Int): Int {
    val exact = pages.indexOfFirst { page -> page.lines.any { it.paragraph == paragraph && offset >= it.start && (offset < it.end || it.image) } }
    if (exact >= 0) return exact
    return pages.indexOfLast { page -> page.lines.firstOrNull()?.let { it.paragraph <= paragraph } == true }.coerceAtLeast(0)
}

/** Android key codes, kept pure so remote/page-turner mapping can be regression tested. */
fun readerKeyDirection(keyCode: Int, volumeKeys: Boolean): Int = when (keyCode) {
    92, 19, 21 -> -1 // Page Up, D-pad Up/Left
    93, 20, 22, 62 -> 1 // Page Down, D-pad Down/Right, Space
    24 -> if (volumeKeys) -1 else 0
    25 -> if (volumeKeys) 1 else 0
    else -> 0
}
