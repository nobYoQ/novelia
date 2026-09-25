package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/** A cloud favorite may add a local copy, but never move an existing manual favorite. */
fun LibraryState.withCloudFavoriteLocalCopy(book: BookCard): LibraryState {
    if(!autoSaveCloudFavoritesLocally || books.any { it.book.ref == book.ref }) return this
    return copy(books = books + SavedBook(book))
}
