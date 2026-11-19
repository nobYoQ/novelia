package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.withKnownUpdateTime
import cc.novelia.app.data.sync.pendingFavoriteAction
import cc.novelia.app.data.updates.updateSnapshot

/** 云端收藏可新增本地副本，但不得移动用户已有的手动收藏。 */
fun LibraryState.withCloudFavoriteLocalCopy(book: BookCard, cloudFolderTitle: String? = null): LibraryState {
    if(!autoSaveCloudFavoritesLocally || !book.ref.isOnline || books.any { it.book.ref == book.ref }) return this
    val folder = cloudFolderTitle?.trim()?.takeIf { it in folders } ?: DEFAULT_FOLDER
    return copy(books = books + newSavedBook(book, folder))
}

internal enum class CloudCopyResult { LocalOnly, LoginRequired, AlreadySaved, Saved, Queued }

internal suspend fun LibraryState.copyLocalFavoriteToCloud(ref: BookRef, folder: String, account: String?,
    currentFolder: suspend () -> String?, cloudFolders: suspend () -> CloudFolders, save: suspend (String) -> Boolean): CloudCopyResult {
    if(!autoSaveLocalFavoritesToCloud || !ref.isOnline) return CloudCopyResult.LocalOnly
    if(account == null) return CloudCopyResult.LoginRequired
    val exists = when(pendingFavoriteAction(pending, account, ref)?.method) {
        "PUT" -> true
        "DELETE" -> false
        else -> !currentFolder().isNullOrBlank()
    }
    if(exists) return CloudCopyResult.AlreadySaved
    return if(save(cloudFolders().autoCopyFolder(ref, folder))) CloudCopyResult.Queued else CloudCopyResult.Saved
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
        require(book.ref.isOnline) { "本地书目没有云端收藏夹" }
        val title = (if(book.ref.isWenku) wenku else web)[book.favored]
        require(!title.isNullOrBlank() && title != "全部" && title.none(Char::isISOControl)) {
            "无法匹配「${book.title}」的云端收藏夹，请刷新云端收藏后重试"
        }
        book.ref.key to title
    }
    return withGroupedCloudFavorites(selected, destinations)
}

internal fun LibraryState.withGroupedCloudFavorites(selected: List<BookCard>, destinations: Map<String, String>): LibraryState {
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
            if(book.ref.isOnline) it.copy(total = maxOf(it.total, baseline.total)) else it
        }
        if(book.ref.isOnline) snapshots[book.ref.key] = baseline
        newSavedBook(merged, destination(book))
    }
    return copy(books = books + added, folders = (folders + targetFolders).distinct(), updateSnapshots = snapshots)
}
