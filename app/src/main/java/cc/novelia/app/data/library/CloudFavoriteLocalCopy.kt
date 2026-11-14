package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.withKnownUpdateTime
import cc.novelia.app.data.updates.updateSnapshot

/** 云端收藏可新增本地副本，但不得移动用户已有的手动收藏。 */
fun LibraryState.withCloudFavoriteLocalCopy(book: BookCard): LibraryState {
    if(!autoSaveCloudFavoritesLocally || books.any { it.book.ref == book.ref }) return this
    return copy(books = books + newSavedBook(book))
}

/** 用户显式批量加入本地，不受自动副本开关影响；已有收藏的资料和分组保持不变。 */
fun LibraryState.withCloudFavoritesAddedLocally(selected: List<BookCard>, folder: String): LibraryState {
    require(folder.isNotBlank()) { "收藏夹名称不能为空" }
    return withImportedCloudFavorites(selected, listOf(folder)) { folder }
}

/** 显式按云端归类时，也移动已有书目及其挂载分卷，但保留原书目资料和阅读记录。 */
fun LibraryState.withCloudFavoritesAddedByCloudFolders(selected: List<BookCard>, cloudFolders: CloudFolders): LibraryState {
    if(selected.isEmpty()) return this
    val web = cloudFolders.favoredWeb.associate { it.id to it.title.trim() }
    val wenku = cloudFolders.favoredWenku.associate { it.id to it.title.trim() }
    val destinations = selected.distinctBy { it.ref }.associate { book ->
        require(!book.ref.isLocal) { "本地文件没有云端收藏夹" }
        val title = (if(book.ref.isWenku) wenku else web)[book.favored]
        require(!title.isNullOrBlank() && title != "全部" && title.none(Char::isISOControl)) {
            "无法匹配「${book.title}」的云端收藏夹，请刷新云端收藏后重试"
        }
        book.ref.key to title
    }
    val moved = copy(books = books.map { saved ->
        val folder = destinations[saved.book.ref.key] ?: saved.parentWenkuKey?.let(destinations::get)
        if(folder == null || folder == saved.folder) saved else saved.copy(folder = folder)
    })
    return moved.withImportedCloudFavorites(selected, destinations.values.toList()) { destinations.getValue(it.ref.key) }
}

private fun LibraryState.withImportedCloudFavorites(selected: List<BookCard>, targetFolders: List<String>,
    destination: (BookCard) -> String): LibraryState {
    val existing = books.map { it.book.ref }.toSet()
    val snapshots = updateSnapshots.toMutableMap()
    val added = selected.distinctBy { it.ref }.filterNot { it.ref in existing }.map { book ->
        val baseline = snapshots[book.ref.key] ?: book.updateSnapshot()
        val merged = book.withKnownUpdateTime(null).let {
            if(book.ref.isLocal) it else it.copy(total = maxOf(it.total, baseline.total))
        }
        if(!book.ref.isLocal) snapshots[book.ref.key] = baseline
        newSavedBook(merged, destination(book))
    }
    return copy(books = books + added, folders = (folders + targetFolders).distinct(), updateSnapshots = snapshots)
}
