package cc.novelia.app.data.model

object ReaderStatusItems {
    val labels = linkedMapOf("time" to "时间", "battery" to "电量", "chapter" to "章节名", "pages" to "本章页码", "progress" to "本章进度")
    val defaults = listOf("time", "chapter", "pages", "progress")
    fun valid(items: List<String>) = items.size <= labels.size && items.distinct().size == items.size && items.all { it in labels }
    /** 固定每格最小宽度，时钟或页数变化不触发正文重新分页。 */
    fun columns(widthDp: Float, fontScale: Float): Int = ((widthDp - 32f + 8f) / (80f * fontScale.coerceAtLeast(1f) + 8f)).toInt().coerceIn(1, 6)

    /** 章节名至少预留两格；拥挤时独占首行，其余项目保留用户指定的相对顺序。 */
    fun layout(items: List<String>, columns: Int): List<List<String>> {
        val capacity = columns.coerceAtLeast(1)
        if("chapter" !in items) return items.chunked(capacity)
        if(items.size + 1 <= capacity || items.size == 1) return listOf(items)
        return listOf(listOf("chapter")) + items.filterNot { it == "chapter" }.chunked(capacity)
    }
}
