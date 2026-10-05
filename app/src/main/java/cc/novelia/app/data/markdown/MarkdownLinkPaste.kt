package cc.novelia.app.data.markdown

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 返回用于替换单行选区的链接；不适用时返回 null，继续原有的粘贴行为。 */
internal fun markdownLinkForPaste(text: String, start: Int, end: Int, clipboard: String,
    limit: Int, unicodeLength: Boolean = false): String? {
    val from = minOf(start, end)
    val to = maxOf(start, end)
    if(from < 0 || to > text.length || from == to || limit < 0) return null
    fun splitsSurrogate(index: Int) = index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
    if(splitsSurrogate(from) || splitsSurrogate(to)) return null
    val selection = text.substring(from, to)
    if(selection.any { it == '\r' || it == '\n' }) return null
    val pasted = clipboard.trim()
    if((!pasted.startsWith("https://", true) && !pasted.startsWith("http://", true)) ||
        pasted.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = pasted.toHttpUrlOrNull() ?: return null
    val label = buildString {
        selection.forEach { if(it == '\\' || it == '[' || it == ']') append('\\'); append(it) }
    }
    val destination = url.toString().replace("(", "%28").replace(")", "%29")
    val link = "[$label]($destination)"
    fun length(value: String) = if(unicodeLength) value.codePointCount(0, value.length) else value.length
    return link.takeIf { length(text).toLong() - length(selection) + length(link) <= limit }
}
