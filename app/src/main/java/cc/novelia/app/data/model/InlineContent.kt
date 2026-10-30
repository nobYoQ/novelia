package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

/** 区间始终使用正文的 UTF-16 下标；注音和脚注正文不占用正文字符。 */
@Serializable enum class InlineKind { Bold, Italic, Underline, Strike, Ruby, Note }

@Serializable data class InlineContent(val start: Int, val end: Int, val kind: InlineKind, val value: String = "") {
    fun validFor(text: String): Boolean = start >= 0 && end > start && end <= text.length &&
        !(start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) &&
        !(end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) &&
        (kind !in setOf(InlineKind.Ruby, InlineKind.Note) || value.isNotBlank())
}
