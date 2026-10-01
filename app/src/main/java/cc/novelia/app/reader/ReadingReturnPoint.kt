package cc.novelia.app.reader

import cc.novelia.app.data.model.Position
import kotlinx.serialization.Serializable

/** 一次查阅会话只记住最初的阅读位置，后续搜索或目录跳转不会覆盖它。 */
@Serializable internal data class ReadingReturnPoint(val position: Position, val sourceIndex: Int? = null) {
    fun resolvedPosition(paragraphs: List<ReadingParagraph>): Position {
        val paragraph = sourceIndex?.let { source -> paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } }
        return position.copy(index = paragraph?.plus(1) ?: position.index.coerceIn(0, paragraphs.size),
            offset = if(position.textOffset > 0) 0 else position.offset)
    }
}

internal fun retainReadingReturnPoint(existing: ReadingReturnPoint?, current: ReadingReturnPoint) = existing ?: current
