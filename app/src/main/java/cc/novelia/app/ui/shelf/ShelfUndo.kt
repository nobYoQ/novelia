package cc.novelia.app.ui.shelf

import cc.novelia.app.data.library.withoutBooks
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/**
 * 将移除前后快照作字段差异比较，只撤销仍与本次移除结果相同的字段，保留期间的新编辑。
 * 不恢复整份书库快照；若父书目已被另行移除，恢复分卷时解绑，并清除失效的顺序引用。
 */
internal fun restoreRemovedShelfBook(current: LibraryState, before: List<SavedBook>, ref: BookRef): LibraryState {
    return restoreRemovedShelfBooks(current, before, setOf(ref))
}

internal fun restoreRemovedShelfBooks(current: LibraryState, before: List<SavedBook>, refs: Set<BookRef>): LibraryState {
    val existing = current.books.map { it.book.ref }.toSet()
    val removed = before.filter { it.book.ref in refs && it.book.ref !in existing }
    if(removed.isEmpty()) return current
    val oldByKey = before.associateBy { it.book.ref.key }
    val afterByKey = LibraryState(books = before).withoutBooks(refs).books.associateBy { it.book.ref.key }
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
        // 等待撤销期间，其他操作可能已经移除了此分卷的父书目。
        saved.copy(parentWenkuKey = saved.parentWenkuKey?.takeIf { it in parents })
    }
    val mounted = restored.groupBy { it.parentWenkuKey }.mapValues { (_, volumes) -> volumes.map { it.book.ref.key }.toSet() }
    return current.copy(books = restored.map { saved ->
        saved.copy(volumeOrder = saved.volumeOrder.filter { it in mounted[saved.book.ref.key].orEmpty() })
    })
}
