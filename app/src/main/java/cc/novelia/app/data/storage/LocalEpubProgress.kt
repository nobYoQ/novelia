package cc.novelia.app.data.storage

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.toReaderChapter
import cc.novelia.app.reader.projectParagraphs
import cc.novelia.app.reader.startIndex
import cc.novelia.app.reader.validLocalGroups

/** 对照或空白升级后仍定位到同一段；重置已失效的段内偏移，迁移不改变阅读时间。 */
internal fun LibraryState.withRecoveredEpubChapter(id: String, previous: LocalChapter, restored: LocalChapter): LibraryState {
    if (previous.readingContent.groups == restored.readingContent.groups && previous.paragraphs == restored.paragraphs) return this
    val key = BookRef("local", id).key
    val settings = bookSettings[key] ?: reader
    val chapter = restored.toReaderChapter()
    val anchors = buildMap {
        for (group in validLocalGroups(chapter)) {
            (group.original + group.translations.flatten()).forEach { put(it, group.startIndex()) }
        }
    }
    val oldProjection = projectParagraphs(previous.toReaderChapter(), settings)
    val projection = projectParagraphs(chapter, settings)
    val current = positions[key]?.takeIf { it.chapterId == restored.id }
    val migrated = current?.let { position ->
        val source = oldProjection.getOrNull(position.index - 1)?.index
        val anchor = source?.let { anchors[it] ?: it }
        val target = anchor?.let { projection.indexOfFirst { it.index >= anchor }.takeIf { it >= 0 } }
        position.copy(index = target?.plus(1) ?: position.index, offset = 0, textOffset = 0, paragraphCount = projection.size)
    }
    return copy(positions = if (migrated == null) positions else positions + (key to migrated),
        notes = notes.map { note ->
            if (note.key == key && note.chapterId == restored.id) note.copy(paragraph = anchors[note.paragraph] ?: note.paragraph)
            else note
        })
}
