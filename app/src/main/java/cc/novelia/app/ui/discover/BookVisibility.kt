package cc.novelia.app.ui.discover

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState

fun visibleBook(book: BookCard, state: LibraryState) = book.ref.key !in state.blockedBooks && book.tags.none { it in state.blockedTags }
