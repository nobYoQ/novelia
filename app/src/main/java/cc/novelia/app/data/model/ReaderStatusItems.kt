package cc.novelia.app.data.model

object ReaderStatusItems {
    val labels = linkedMapOf("time" to "时间", "battery" to "电量", "chapter" to "章节名", "pages" to "本章页码", "progress" to "本章进度")
    val defaults = listOf("time", "chapter", "pages", "progress")
    fun valid(items: List<String>) = items.size <= labels.size && items.distinct().size == items.size && items.all { it in labels }
    /** 固定每格最小宽度，时钟或页数变化不触发正文重新分页。 */
    fun columns(widthDp: Float, fontScale: Float): Int = ((widthDp - 32f) / (80f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 5)
    fun rows(count: Int, columns: Int): Int = if(count == 0) 0 else (count + columns - 1) / columns
}
