package cc.novelia.app.ui

import cc.novelia.app.data.*

internal fun downloadRecoveryLabel(status: String): String = when(status) {
    "需要登录" -> "登录后继续"
    "已暂停" -> "重新开始"
    else -> "重试"
}

internal fun pendingFavoriteAction(pending: List<PendingAction>, account: String, ref: BookRef): PendingAction? {
    val prefix = if(ref.isWenku) "user/favored-wenku/" else "user/favored-web/"
    val suffix = "/${if(ref.isWenku) ref.id else ref.key}"
    return pending.lastOrNull { it.account == account && it.path.startsWith(prefix) && it.path.substringAfter(prefix).substringAfter('/') == suffix.removePrefix("/") }
}

internal data class NotePresentation(val note: Note, val bookTitle: String, val chapterTitle: String)

internal fun presentNotes(state: LibraryState, query: String = "", bookKey: String? = null): List<NotePresentation> {
    val books = state.books.associateBy { it.book.ref.key }
    val text = query.trim()
    return state.notes.asSequence().filter { bookKey == null || it.key == bookKey }.map { note ->
        val book = note.bookTitle.ifBlank { books[note.key]?.book?.title ?: "未命名作品" }
        val chapter = note.chapterTitle.ifBlank {
            state.positions[note.key]?.takeIf { it.chapterId == note.chapterId }?.title?.ifBlank { null }
                ?: "章节 ${note.chapterId}"
        }
        NotePresentation(note, book, chapter)
    }.filter { text.isBlank() || listOf(it.bookTitle, it.chapterTitle, it.note.quote, it.note.text).any { value -> value.contains(text, ignoreCase = true) } }
        .sortedByDescending { it.note.createdAt }.toList()
}

internal fun stableCoverVariant(key: String, count: Int): Int = (key.hashCode() and Int.MAX_VALUE) % count

/** Restore only the removal's changes; concurrent folder/mount edits must win. */
internal fun restoreRemovedShelfBook(current: LibraryState, before: List<SavedBook>, ref: BookRef): LibraryState {
    if(current.books.any { it.book.ref == ref }) return current
    val removed = before.firstOrNull { it.book.ref == ref } ?: return current
    val oldByKey = before.associateBy { it.book.ref.key }
    val afterByKey = LibraryState(books = before).withoutBook(ref).books.associateBy { it.book.ref.key }
    val books = current.books.map { saved ->
        val old = oldByKey[saved.book.ref.key]
        val after = afterByKey[saved.book.ref.key]
        if(old == null || after == null) saved else {
            val restoreMount = old.parentWenkuKey != after.parentWenkuKey &&
                saved.parentWenkuKey == after.parentWenkuKey && saved.folder == after.folder
            saved.copy(
                parentWenkuKey = if(restoreMount) old.parentWenkuKey else saved.parentWenkuKey,
                folder = if(restoreMount) old.folder else saved.folder,
                volumeOrder = if(old.volumeOrder != after.volumeOrder && saved.volumeOrder == after.volumeOrder) old.volumeOrder else saved.volumeOrder,
            )
        }
    }
    val candidates = books + removed
    val parents = candidates.filter { it.book.ref.isWenku }.map { it.book.ref.key }.toSet()
    val restored = candidates.map { saved ->
        // Another operation may have removed this volume's parent while its undo was pending.
        saved.copy(parentWenkuKey = saved.parentWenkuKey?.takeIf { it in parents })
    }
    val mounted = restored.groupBy { it.parentWenkuKey }.mapValues { (_, volumes) -> volumes.map { it.book.ref.key }.toSet() }
    return current.copy(books = restored.map { saved ->
        saved.copy(volumeOrder = saved.volumeOrder.filter { it in mounted[saved.book.ref.key].orEmpty() })
    })
}
