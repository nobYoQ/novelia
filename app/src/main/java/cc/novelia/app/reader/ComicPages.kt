package cc.novelia.app.reader

import java.io.File

/** 阅读器只消费已按阅读顺序排列的本地图片，站点适配器也可以提供自己的图片缓存。 */
internal data class ComicPage(val file: File, val width: Int, val height: Int, val orientation: Int = 1,
    val regionDecodable: Boolean = true)

internal data class ComicStrip(val page: Int, val top: Int, val bottom: Int)

internal class ComicStripLayout(private val pages: List<ComicPage>) {
    val firstStrip = IntArray(pages.size + 1)
    val size get() = firstStrip.last()
    init {
        pages.forEachIndexed { index, page ->
            val step = 2L * page.width
            firstStrip[index + 1] = firstStrip[index] + ((page.height + step - 1) / step).toInt()
        }
    }

    operator fun get(index: Int): ComicStrip {
        val hit = firstStrip.binarySearch(index)
        val page = if (hit >= 0) hit else -hit - 2
        val step = 2L * pages[page].width
        val top = (index - firstStrip[page]) * step
        return ComicStrip(page, top.toInt(), minOf(top + step, pages[page].height.toLong()).toInt())
    }

    fun stripAt(page: Int, sourceY: Int): Int {
        val y = sourceY.coerceIn(0, pages[page].height - 1)
        return firstStrip[page] + (y / (2L * pages[page].width)).toInt()
    }
}
