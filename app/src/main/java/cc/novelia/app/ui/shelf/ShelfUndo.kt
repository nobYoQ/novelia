package cc.novelia.app.ui.shelf

import cc.novelia.app.data.library.withoutBook
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

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
