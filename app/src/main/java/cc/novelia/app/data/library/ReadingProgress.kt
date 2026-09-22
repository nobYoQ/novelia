package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position

/** Save the real reading anchor and acknowledge chapters only after reaching the known ending. */
fun LibraryState.withReadingPosition(ref: BookRef, position: Position): LibraryState {
    if(historyPaused) return this
    val previous = positions[ref.key]?.takeIf { it.chapterId == position.chapterId }
    // Note jumps provide just an anchor. They must not erase known completion of the same chapter.
    val next = position.copy(
        chapterIndex = position.chapterIndex ?: previous?.chapterIndex,
        chapterCount = position.chapterCount ?: previous?.chapterCount,
        paragraphCount = position.paragraphCount ?: previous?.paragraphCount,
        chapterCompleted = position.chapterCompleted || previous?.chapterCompleted == true
    )
    return copy(positions = positions + (ref.key to next)).acknowledgeReadChapterUpdates(ref)
}

/** Keep translation freshness and update-check baselines; a completed old directory is not current. */
fun LibraryState.acknowledgeReadChapterUpdates(ref: BookRef): LibraryState {
    if(ref.isWenku) return this
    val position = positions[ref.key] ?: return this
    val count = position.chapterCount ?: return this
    if(!position.chapterCompleted || count <= 0 || position.chapterIndex != count - 1) return this
    val saved = books.firstOrNull { it.book.ref == ref } ?: return this
    if(count < maxOf(saved.book.total, updateSnapshots[ref.key]?.total ?: 0)) return this
    val remaining = bookUpdates[ref.key]?.copy(newChapters = 0)?.takeIf { it.hasChanges }
    if(saved.hasUpdates == (remaining != null) && bookUpdates[ref.key] == remaining) return this
    return copy(
        books = books.map { if(it.book.ref == ref) it.copy(hasUpdates = remaining != null) else it },
        bookUpdates = if(remaining == null) bookUpdates - ref.key else bookUpdates + (ref.key to remaining)
    )
}
