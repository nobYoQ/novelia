package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings

/**
 * 精确搜索命中：paragraph 是投影列表下标，part 是其中的语言片段下标。
 * start/end 是该片段未加缩进前的 UTF-16 区间 [start, end)，可直接用于 Kotlin 字符串截取。
 */
data class ReadingTextMatch(val paragraph: Int, val part: Int, val start: Int, val end: Int)

// 同一原始段落的不同语言之间，视觉间距小于独立段落之间的间距。
internal const val READING_PART_SEPARATOR = "\n"

internal fun ReadingTextMatch.textOffset(paragraph: ReadingParagraph, settings: ReaderSettings): Int =
    paragraphPartStarts(paragraph, settings).getOrElse(part) { 0 } + (if(settings.indent) 2 else 0) + start

internal fun readerPartFontSize(settings: ReaderSettings, secondary: Boolean): Float = settings.fontSize - if(secondary) 1f else 0f
internal fun readerPartLineHeight(settings: ReaderSettings, secondary: Boolean): Float = readerPartFontSize(settings, secondary) * settings.lineHeight

/** 从当前搜索命中继续，或从长段落中实际可见的字符位置开始。 */
internal fun nextReadingMatchIndex(matches: List<ReadingTextMatch>, current: ReadingTextMatch?, direction: Int,
    paragraph: Int, offset: Int, textOffset: (ReadingTextMatch) -> Int): Int {
    if(matches.isEmpty()) return -1
    val selected = matches.indexOf(current)
    if(selected >= 0) return (selected + direction + matches.size) % matches.size
    return if(direction < 0) matches.indexOfLast { it.paragraph < paragraph || (it.paragraph == paragraph && textOffset(it) <= offset) }.takeIf { it >= 0 } ?: matches.lastIndex
    else matches.indexOfFirst { it.paragraph > paragraph || (it.paragraph == paragraph && textOffset(it) >= offset) }.takeIf { it >= 0 } ?: 0
}

/**
 * 按段落、语言片段、出现位置的稳定顺序查找，包含相互重叠的命中。
 * 下一次搜索从 start + 1 开始而不是 end，例如“哈哈哈”中的“哈哈”会命中两次。
 * 插图不参与搜索；maxResults 和取消回调限制超长章节的计算成本。
 */
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

/**
 * 计算各语言片段在整段显示文本中的起点，计入片段分隔符和并列译文标签。
 * 起点位于本片段缩进之前；命中转为分页锚点时还需加缩进长度。
 * 必须与 EInkPage 的文本拼接规则同步，否则搜索高亮和恢复位置会偏移。
 */
internal fun paragraphPartStarts(paragraph: ReadingParagraph, settings: ReaderSettings): List<Int> {
    var length = 0
    return paragraph.parts.mapIndexed { index, part ->
        if(index > 0) length += READING_PART_SEPARATOR.length
        if(settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) length += part.source.uppercase().length + 1
        val start = length
        length += (if(settings.indent) 2 else 0) + part.text.length
        start
    }
}

internal data class ReadingAnchorLine(val start: Int, val end: Int, val top: Int)
/** 用已测量的行在字符偏移与纵向像素间转换；按文本顺序合并各片段后进行二分定位。 */
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
