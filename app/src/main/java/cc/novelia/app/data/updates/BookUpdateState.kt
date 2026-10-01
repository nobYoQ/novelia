package cc.novelia.app.data.updates

import cc.novelia.app.data.library.acknowledgeReadChapterUpdates
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.withKnownUpdateTime

/** 收藏元数据替换前保留基线，移动收藏夹不能吞掉下一轮更新。 */
fun LibraryState.withSavedBook(book: BookCard, folder: String = "默认收藏"): LibraryState {
    val previous = books.firstOrNull { it.book.ref == book.ref }
    val baseline = updateSnapshots[book.ref.key] ?: (previous?.book ?: book).updateSnapshot()
    val merged = book.withKnownUpdateTime(previous?.book).let {
        if(book.ref.isLocal) it else it.copy(total = maxOf(it.total, previous?.book?.total ?: 0, baseline.total))
    }
    return copy(
        books = books.filterNot { it.book.ref == book.ref } + (previous?.copy(book = merged, folder = folder) ?: SavedBook(merged, folder)),
        updateSnapshots = if(book.ref.isLocal) updateSnapshots else updateSnapshots + (book.ref.key to baseline)
    )
}

/** 详情刷新和后台检查共用同一增量计算；较早开始的迟到请求不得回退基线。 */
fun LibraryState.withBookUpdate(book: BookCard, observedAt: Long): LibraryState {
    val ref = book.ref
    val saved = books.firstOrNull { it.book.ref == ref } ?: return this
    if(ref.isLocal) return this
    val previous = updateSnapshots[ref.key] ?: saved.book.updateSnapshot()
    if(observedAt < previous.checkedAt) return this
    val snapshot = book.updateSnapshot(observedAt)
    val delta = detectBookUpdate(previous, snapshot, ref.isWenku)
    val prior = bookUpdates[ref.key]
    val changes = if(delta.hasChanges) prior?.accumulate(delta) ?: delta else prior
    return copy(
        books = books.map { if(it.book.ref == ref) it.copy(book = book.withKnownUpdateTime(it.book), hasUpdates = it.hasUpdates || changes?.hasChanges == true) else it },
        updateSnapshots = updateSnapshots + (ref.key to snapshot),
        bookUpdates = if(changes == null) bookUpdates - ref.key else bookUpdates + (ref.key to changes)
    ).acknowledgeReadChapterUpdates(ref, book.cloudReading?.account)
}

/** 手动确认只清除提示，保留译文缓存刷新时间和数量基线。 */
fun LibraryState.withAcknowledgedBookUpdates(ref: BookRef): LibraryState {
    val remaining = bookUpdates[ref.key]?.acknowledgeThrough(Long.MAX_VALUE)?.copy(newVolumes = 0)
        ?.takeIf { it.translationUpdatedAt.isNotEmpty() }
    return copy(
        books = books.map { if(it.book.ref == ref) it.copy(hasUpdates = false) else it },
        bookUpdates = if(remaining == null) bookUpdates - ref.key else bookUpdates + (ref.key to remaining)
    )
}
