package cc.novelia.app.data.model

object ReaderStatusItems {
    val labels = linkedMapOf("time" to "时间", "battery" to "电量", "book" to "书名", "chapter" to "章节名", "pages" to "本章页码", "progress" to "本章进度")
    val defaults = listOf("time", "chapter", "pages", "progress")
    val topDefaults = listOf("book")
    fun valid(items: List<String>) = items.size <= labels.size && items.distinct().size == items.size && items.all { it in labels }
    const val compactWidthDp = 64f
    /** 12sp 的短信息使用紧凑格宽，320dp 常规字号可并排四项；变化中的内容不触发重新分页。 */
    fun columns(widthDp: Float, fontScale: Float): Int = ((widthDp - 32f + 8f) / (compactWidthDp * fontScale.coerceAtLeast(1f) + 8f)).toInt().coerceIn(1, 7)

    fun isTitle(item: String) = item == "book" || item == "chapter"

    fun layout(items: List<String>, columns: Int): List<List<String>> {
        val capacity = columns.coerceAtLeast(1)
        return buildList {
            var row = mutableListOf<String>()
            var used = 0
            items.forEachIndexed { index, item ->
                val cost = if(isTitle(item)) minOf(3, capacity) else 1
                val needed = if(!isTitle(item) && row.lastOrNull()?.let(::isTitle) == true)
                    items.drop(index).takeWhile { !isTitle(it) }.size.coerceAtMost(capacity) else cost
                if(used + needed > capacity) { add(row); row = mutableListOf(); used = 0 }
                row.add(item)
                used += cost
            }
            if(row.isNotEmpty()) add(row)
        }
    }
}
