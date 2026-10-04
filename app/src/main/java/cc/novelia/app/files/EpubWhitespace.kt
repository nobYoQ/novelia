package cc.novelia.app.files

/** 普通 HTML 空白折叠；中日文之间的源码换行不生成空格，显式空格和全角空格仍保留。 */
internal fun normalizeEpubWhitespace(text: String, checkCancelled: () -> Unit = {}): String {
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (index % 4096 == 0) checkCancelled()
        if (!text[index].isHtmlWhitespace()) {
            result.append(text[index++])
            continue
        }
        var lineBreak = false
        while (index < text.length && text[index].isHtmlWhitespace()) {
            if (index % 4096 == 0) checkCancelled()
            lineBreak = lineBreak || text[index] == '\n' || text[index] == '\r'
            index++
        }
        if (result.isEmpty() || index == text.length) continue
        val joinsEastAsianText = lineBreak && Character.codePointBefore(result, result.length).isEastAsianText() &&
            text.codePointAt(index).isEastAsianText()
        if (!joinsEastAsianText) result.append(' ')
    }
    return result.toString().trim()
}

private fun Char.isHtmlWhitespace() = this == ' ' || this == '\t' || this == '\r' || this == '\n' || this == '\u000C'

private fun Int.isEastAsianText(): Boolean = when (Character.UnicodeScript.of(this)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> this in 0x3001..0x30FF || this in 0xFF01..0xFF9F
}
