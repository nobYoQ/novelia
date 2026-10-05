package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReadingHistoryEntry

/** 只从旧位置迁移一次，之后清空历史或暂停记录都不会重新生成旧历史。 */
fun LibraryState.withMigratedReadingHistory(): LibraryState {
    if(historyMigrated) return this
    val booksByKey = books.associateBy { it.book.ref.key }
    val migrated = positions.mapValues { (key, position) -> ReadingHistoryEntry(key,
        booksByKey[key]?.book?.title.orEmpty(), position.chapterId, position.title, position.updatedAt) }
    return copy(readingHistory = migrated + readingHistory, historyMigrated = true)
}

/**
 * 保存真实阅读锚点；同一章的完成标记只增不减，回看或笔记跳转仍能更新位置。
 * historyPaused 只暂停历史，续读位置始终保存；缺少的目录计数沿用该章已知值。
 */
fun LibraryState.withReadingPosition(ref: BookRef, position: Position, bookTitle: String = ""): LibraryState {
    val state = withMigratedReadingHistory()
    val previous = state.positions[ref.key]?.takeIf { it.chapterId == position.chapterId }
    // 从笔记跳转只提供锚点，不应抹去同一章已有的读完状态。
    val next = position.copy(
        chapterIndex = position.chapterIndex ?: previous?.chapterIndex,
        chapterCount = position.chapterCount ?: previous?.chapterCount,
        paragraphCount = position.paragraphCount ?: previous?.paragraphCount,
        chapterCompleted = position.chapterCompleted || previous?.chapterCompleted == true
    )
    val saved = state.copy(positions = state.positions + (ref.key to next))
    if(state.historyPaused) return saved.acknowledgeReadChapterUpdates(ref)
    val history = ReadingHistoryEntry(ref.key, bookTitle.takeIf { it.isNotBlank() }
        ?: state.books.firstOrNull { it.book.ref == ref }?.book?.title
        ?: state.readingHistory[ref.key]?.bookTitle.orEmpty(), next.chapterId, next.title, next.updatedAt)
    return saved.copy(readingHistory = state.readingHistory + (ref.key to history)).acknowledgeReadChapterUpdates(ref)
}

/**
 * 按已到达的章节逐章确认新增内容；进入最新已知章节即可确认本轮更新。
 * 读完之后才发现的译文增量继续保留；译文新鲜度时间和检查基线始终留给缓存刷新使用。
 * 文库父书目不在此确认，因为实际阅读的是单独导入的本地分卷。
 */
fun LibraryState.acknowledgeReadChapterUpdates(ref: BookRef, account: String? = null): LibraryState {
    if(ref.isWenku) return this
    val saved = books.firstOrNull { it.book.ref == ref } ?: return this
    val position = positions[ref.key]
    val cloud = saved.book.cloudReading?.takeIf { account != null && it.account == account && it.chapterResolved }
    val localReached = position?.chapterIndex?.takeIf { it in 0 until (position.chapterCount ?: 0) }?.plus(1) ?: 0
    val cloudReached = cloud?.chapterIndex?.takeIf { it in 0 until (cloud.chapterCount ?: 0) }?.plus(1) ?: 0
    val reached = maxOf(localReached, cloudReached)
    if(reached == 0) return this
    val total = maxOf(saved.book.total, updateSnapshots[ref.key]?.total ?: 0,
        if(localReached > 0) position?.chapterCount ?: 0 else 0, if(cloudReached > 0) cloud?.chapterCount ?: 0 else 0)
    val chaptersAhead = (total - reached).coerceAtLeast(0)
    val readAt = maxOf(if(localReached == total) position?.updatedAt ?: 0L else 0L,
        if(cloudReached == total) (cloud?.lastReadAt ?: 0L) * 1000 else 0L)
    val update = bookUpdates[ref.key]
    val remaining = (if(chaptersAhead == 0) update?.acknowledgeThrough(readAt)
        else update?.copy(newChapters = minOf(update.newChapters, chaptersAhead)))
        ?.takeIf { it.hasChanges || it.translationUpdatedAt.isNotEmpty() }
    val hasUpdates = remaining?.hasChanges == true || (update == null && saved.hasUpdates && chaptersAhead > 0)
    if(saved.hasUpdates == hasUpdates && bookUpdates[ref.key] == remaining) return this
    return copy(
        books = books.map { if(it.book.ref == ref) it.copy(hasUpdates = hasUpdates) else it },
        bookUpdates = if(remaining == null) bookUpdates - ref.key else bookUpdates + (ref.key to remaining)
    )
}

/** 兼容修正旧版已到达章节的更新记录，无需用户重新打开阅读器。 */
fun LibraryState.acknowledgeCompletedBookUpdates(account: String? = null): LibraryState {
    val candidates = books.filter { it.hasUpdates || bookUpdates[it.book.ref.key]?.hasChanges == true }
    return candidates.fold(this) { state, saved -> state.acknowledgeReadChapterUpdates(saved.book.ref, account) }
}
