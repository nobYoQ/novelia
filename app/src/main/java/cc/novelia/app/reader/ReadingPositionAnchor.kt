package cc.novelia.app.reader

import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.webdav.WebDavProjection
import java.security.MessageDigest

/** 仅保存不可逆的文本摘要，正文仍由章节缓存提供；长度前缀避免片段拼接碰撞。 */
fun ReadingParagraph.readingTextHash(): String {
    val value = buildString {
        append("novelia-reading-text-v1:")
        fun field(text: String) { append(text.length).append(':').append(text) }
        field(imageUrl.orEmpty())
        field(localImageId.orEmpty())
        parts.forEach { part -> field(part.source); field(part.text) }
    }
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 按原始段落恢复投影坐标；跨语言或译源时只恢复段落，避免把字符偏移套到其他文本。 */
fun Position.resolvedReadingPosition(paragraphs: List<ReadingParagraph>, settings: ReaderSettings): Position {
    val projected = sourceParagraph?.let { source ->
        paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } ?: paragraphs.lastIndex.coerceAtLeast(0)
    }
    val compatibleSource = anchorSource == null || anchorSource == WebDavProjection.anchorSource(settings)
    val nextIndex = if(paragraphs.isEmpty()) 0 else projected?.plus(1) ?: index.coerceIn(0, paragraphs.size)
    val paragraph = paragraphs.getOrNull(nextIndex - 1)
    val sameParagraph = sourceParagraph == null || sourceParagraph == paragraph?.index
    val compatibleText = sameParagraph && (anchorTextHash == null || anchorTextHash == paragraph?.readingTextHash())
    return copy(index = nextIndex,
        offset = if(compatibleSource && compatibleText) offset else 0,
        textOffset = if(compatibleSource && compatibleText) textOffset else 0)
}
