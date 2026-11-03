package cc.novelia.app.data.model

object ReaderStatusItems {
    val labels = linkedMapOf("time" to "时间", "battery" to "电量", "chapter" to "章节名", "pages" to "本章页码", "progress" to "本章进度")
    val defaults = listOf("time", "chapter", "pages", "progress")
    fun valid(items: List<String>) = items.size <= labels.size && items.distinct().size == items.size && items.all { it in labels }
    const val compactWidthDp = 64f
    /** 12sp 的短信息使用紧凑格宽，320dp 常规字号可并排四项；变化中的内容不触发重新分页。 */
    fun columns(widthDp: Float, fontScale: Float): Int = ((widthDp - 32f + 8f) / (compactWidthDp * fontScale.coerceAtLeast(1f) + 8f)).toInt().coerceIn(1, 7)

    /** 章节名至少预留三格，避免短信息变窄后挤压标题；其余项目保留用户指定的相对顺序。 */
    fun layout(items: List<String>, columns: Int): List<List<String>> {
        val capacity = columns.coerceAtLeast(1)
        if("chapter" !in items) return items.chunked(capacity)
        if(items.size + 2 <= capacity || items.size == 1) return listOf(items)
        return listOf(listOf("chapter")) + items.filterNot { it == "chapter" }.chunked(capacity)
    }
}
