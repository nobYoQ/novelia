package cc.novelia.app.data.updates

import cc.novelia.app.data.model.SavedBook

/**
 * 从上次持久化游标之后开始，并回绕覆盖整个在线书架，避免系统中断时尾部书目长期饥饿。
 * 本地文件不检查远端更新；游标缺失或书目已删除时，从当前在线列表首项开始。
 */
internal fun booksForUpdate(books: List<SavedBook>, lastCheckedKey: String?): List<SavedBook> {
    val online = books.filterNot { it.book.ref.isLocal }
    val resumeAt = online.indexOfFirst { it.book.ref.key == lastCheckedKey } + 1
    return online.drop(resumeAt) + online.take(resumeAt)
}
