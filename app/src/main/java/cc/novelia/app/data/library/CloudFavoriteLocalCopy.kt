package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/** 云端收藏可新增本地副本，但不得移动用户已有的手动收藏。 */
fun LibraryState.withCloudFavoriteLocalCopy(book: BookCard): LibraryState {
    if(!autoSaveCloudFavoritesLocally || books.any { it.book.ref == book.ref }) return this
    return copy(books = books + SavedBook(book))
}
