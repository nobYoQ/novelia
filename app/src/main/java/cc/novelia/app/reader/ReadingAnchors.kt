package cc.novelia.app.reader

import cc.novelia.app.data.ReaderSettings

/** Character ranges are relative to one displayed language/translation, before indentation. */
data class ReadingTextMatch(val paragraph: Int, val part: Int, val start: Int, val end: Int)

internal fun ReadingTextMatch.textOffset(paragraph: ReadingParagraph, settings: ReaderSettings): Int =
    paragraphPartStarts(paragraph, settings).getOrElse(part) { 0 } + (if(settings.indent) 2 else 0) + start

internal fun readerPartFontSize(settings: ReaderSettings, secondary: Boolean): Float = settings.fontSize - if(secondary) 1f else 0f
internal fun readerPartLineHeight(settings: ReaderSettings, secondary: Boolean): Float = readerPartFontSize(settings, secondary) * settings.lineHeight

/** Continue from the active occurrence, or the actual visible character within a long paragraph. */
internal fun nextReadingMatchIndex(matches: List<ReadingTextMatch>, current: ReadingTextMatch?, direction: Int,
    paragraph: Int, offset: Int, textOffset: (ReadingTextMatch) -> Int): Int {
    if(matches.isEmpty()) return -1
    val selected = matches.indexOf(current)
    if(selected >= 0) return (selected + direction + matches.size) % matches.size
    return if(direction < 0) matches.indexOfLast { it.paragraph < paragraph || (it.paragraph == paragraph && textOffset(it) <= offset) }.takeIf { it >= 0 } ?: matches.lastIndex
    else matches.indexOfFirst { it.paragraph > paragraph || (it.paragraph == paragraph && textOffset(it) >= offset) }.takeIf { it >= 0 } ?: 0
}

/** A stable order across paragraph, translation and every occurrence, including overlaps. */
fun findReadingTextMatches(paragraphs: List<ReadingParagraph>, query: String, maxResults: Int = 2000,
    checkCancelled: () -> Unit = {}): List<ReadingTextMatch> {
    val term = query.trim()
    if(term.isEmpty() || maxResults <= 0) return emptyList()
    return buildList {
        for((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            checkCancelled()
            if(paragraph.imageUrl != null || paragraph.localImageId != null) continue
            for((partIndex, part) in paragraph.parts.withIndex()) {
                var from = 0
                while(from <= part.text.length - term.length) {
                    checkCancelled()
                    val start = part.text.indexOf(term, from, ignoreCase = true)
                    if(start < 0) break
                    add(ReadingTextMatch(paragraphIndex, partIndex, start, start + term.length))
                    if(size >= maxResults) return@buildList
                    from = start + 1
                }
            }
        }
    }
}

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
