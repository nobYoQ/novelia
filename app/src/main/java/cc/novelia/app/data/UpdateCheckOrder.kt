package cc.novelia.app.data

/** Start after the last persisted item, but cover the entire online shelf on every full run. */
internal fun booksForUpdate(books: List<SavedBook>, lastCheckedKey: String?): List<SavedBook> {
    val online = books.filterNot { it.book.ref.isLocal }
    val resumeAt = online.indexOfFirst { it.book.ref.key == lastCheckedKey } + 1
    return online.drop(resumeAt) + online.take(resumeAt)
}
