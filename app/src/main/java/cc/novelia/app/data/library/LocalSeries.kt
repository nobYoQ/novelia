package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.LibraryState

internal fun LibraryState.createLocalSeries(ref: BookRef, title: String, folder: String): LibraryState {
    require(ref.isLocalSeries && books.none { it.book.ref == ref }) { "总集已存在" }
    val book = BookCard(ref, localSeriesTitle(title), subtitle = "本地总集")
    return copy(books = books + newSavedBook(book, folder.takeIf { it in folders } ?: DEFAULT_FOLDER))
}

internal fun LibraryState.renameLocalSeries(ref: BookRef, title: String): LibraryState {
    require(ref.isLocalSeries && books.any { it.book.ref == ref }) { "总集已不存在" }
    val name = localSeriesTitle(title)
    return copy(books = books.map { if(it.book.ref == ref) it.copy(book = it.book.copy(title = name)) else it })
}

private fun localSeriesTitle(title: String) = title.trim().also { name ->
    require(name.isNotEmpty() && name.length <= 120 && name.none(Char::isISOControl)) { "总集名称应为 1 至 120 个字符" }
}
