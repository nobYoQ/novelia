package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.updates.withSavedBook

/** 云端收藏可新增本地副本，但不得移动用户已有的手动收藏。 */
fun LibraryState.withCloudFavoriteLocalCopy(book: BookCard): LibraryState {
    if(!autoSaveCloudFavoritesLocally || books.any { it.book.ref == book.ref }) return this
    return copy(books = books + SavedBook(book))
}

/** 用户显式批量加入本地，不受自动副本开关影响；已有收藏的资料和分组保持不变。 */
fun LibraryState.withCloudFavoritesAddedLocally(selected: List<BookCard>, folder: String): LibraryState {
    require(folder.isNotBlank()) { "收藏夹名称不能为空" }
    val existing = books.map { it.book.ref }.toSet()
    return selected.distinctBy { it.ref }.filterNot { it.ref in existing }.fold(
        copy(folders = (folders + folder).distinct())
    ) { state, book -> state.withSavedBook(book, folder) }
}
