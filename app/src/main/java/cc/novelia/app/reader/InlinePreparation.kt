package cc.novelia.app.reader

import cc.novelia.app.data.model.InlineContent
import cc.novelia.app.data.model.InlineKind
import cc.novelia.app.data.model.ReaderSettings
import com.ibm.icu.text.Replaceable
import com.ibm.icu.text.ReplaceableString
import com.ibm.icu.text.Transliterator

/** 显式文本编辑映射，不通过模糊搜索猜测样式位置；底文改变时撤掉已不可靠的读音。 */
internal fun List<InlineContent>.edited(start: Int, end: Int, length: Int): List<InlineContent> = mapNotNull { mark ->
    val delta = length - (end - start)
    when {
        mark.end <= start -> mark
        mark.start >= end -> mark.copy(start = mark.start + delta, end = mark.end + delta)
        mark.kind == InlineKind.Ruby -> null
        else -> mark.copy(start = if (mark.start < start) mark.start else start,
            end = if (mark.end > end) mark.end + delta else start + length).takeIf { it.end > it.start }
    }
}

private class MarkedReplaceable(text: String, var inline: List<InlineContent>, private val delegate: ReplaceableString = ReplaceableString(text)) : Replaceable by delegate {
    override fun replace(start: Int, limit: Int, text: String) {
        if (delegate.toString().regionMatches(start, text, 0, text.length) && limit - start == text.length) return
        inline = inline.edited(start, limit, text.length)
        delegate.replace(start, limit, text)
    }
    override fun replace(start: Int, limit: Int, chars: CharArray, charsStart: Int, charsLen: Int) =
        replace(start, limit, String(chars, charsStart, charsLen))
    override fun copy(start: Int, limit: Int, dest: Int) = replace(dest, dest, delegate.toString().substring(start, limit))
    override fun toString(): String = delegate.toString()
}

internal fun prepareInlinePart(part: TextPart, settings: ReaderSettings, replacer: ReaderTextReplacer,
    converter: () -> Transliterator, checkCancelled: () -> Unit): Pair<TextPart, Boolean> {
    val text = part.text.trim()
    val leading = part.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    var marks = part.inline.filter { it.validFor(part.text) }.mapNotNull {
        it.copy(start = (it.start - leading).coerceIn(0, text.length), end = (it.end - leading).coerceIn(0, text.length)).takeIf { m -> m.validFor(text) }
    }
    val prepared = if (settings.traditional && part.source != "日文" && !part.source.startsWith("原文")) {
        val result = StringBuilder()
        val convertedMarks = mutableListOf<InlineContent>()
        var start = 0
        while (start < text.length) {
            checkCancelled()
            var end = (start + 4096).coerceAtMost(text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            val chunkMarks = marks.filter { it.end > start && it.start < end }.mapNotNull {
                if (it.kind == InlineKind.Ruby && (it.start < start || it.end > end)) null
                else it.copy(start = maxOf(it.start, start) - start, end = minOf(it.end, end) - start)
            }
            val chunk = MarkedReplaceable(text.substring(start, end), chunkMarks)
            converter().transliterate(chunk)
            convertedMarks += chunk.inline.map { it.copy(start = it.start + result.length, end = it.end + result.length) }
            result.append(chunk)
            start = end
        }
        marks = convertedMarks
        result.toString()
    } else text
    var replacedMarks = marks
    var delta = 0
    val replaced = replacer.replace(prepared, onReplacement = { start, end, replacement ->
        if (prepared.substring(start, end) != replacement) replacedMarks = replacedMarks.edited(start + delta, end + delta, replacement.length)
        delta += replacement.length - (end - start)
    }, checkCancelled = checkCancelled)
    val finalText = replaced ?: prepared
    return part.copy(text = finalText, inline = (if (replaced == null) marks else replacedMarks).filter { it.validFor(finalText) }) to (replaced == null)
}
