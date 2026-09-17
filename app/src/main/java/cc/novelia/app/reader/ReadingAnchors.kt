package cc.novelia.app.reader

import cc.novelia.app.data.ReaderSettings

/** Canonical offsets match the text used by static pagination, including labels/indent. */
internal fun paragraphPartStarts(paragraph: ReadingParagraph, settings: ReaderSettings): List<Int> {
    var length = 0
    return paragraph.parts.mapIndexed { index, part ->
        if(index > 0) length += 2
        if(settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) length += part.source.uppercase().length + 1
        val start = length
        length += (if(settings.indent) 2 else 0) + part.text.length
        start
    }
}

internal data class ReadingAnchorLine(val start: Int, val end: Int, val top: Int)
internal data class ParagraphScrollLayout(val parts: Map<Int, List<ReadingAnchorLine>> = emptyMap()) {
    private val lines = parts.toSortedMap().values.flatten()
    private inline fun lastBefore(value: Int, coordinate: (ReadingAnchorLine) -> Int): ReadingAnchorLine? {
        var low = 0
        var high = lines.size
        while(low < high) {
            val middle = (low + high) ushr 1
            if(coordinate(lines[middle]) <= value) low = middle + 1 else high = middle
        }
        return lines.getOrNull(low - 1)
    }
    fun textOffsetAt(y: Int): Int = lastBefore(y) { it.top }?.start ?: lines.firstOrNull()?.start ?: 0
    fun scrollOffsetAt(textOffset: Int): Int = lastBefore(textOffset) { it.start }?.top ?: 0
}
