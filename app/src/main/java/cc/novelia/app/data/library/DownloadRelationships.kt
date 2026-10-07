package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.DownloadedBookLink
import cc.novelia.app.data.model.LibraryState
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 旧版批量下载没有 sourceBook，从原站下载路径补回身份，绝不按书名匹配。 */
fun DownloadEntry.originBook(): BookRef? = sourceBook ?: sourceCard?.ref ?: run {
    val segments = url.toHttpUrlOrNull()?.pathSegments ?: return@run null
    when {
        segments.take(2) == listOf("api", "wenku") && segments.size == 5 && segments[3] == "file" -> BookRef("wenku", segments[2])
        segments.take(2) == listOf("api", "novel") && segments.size == 5 && segments[4] == "file" -> BookRef(segments[2], segments[3])
        else -> null
    }
}

fun DownloadEntry.originVolumeId(): String? = if(originBook()?.isWenku == true) {
    val segments = url.toHttpUrlOrNull()?.pathSegments.orEmpty()
    if(segments.size == 5 && segments[3] == "file") segments[4] else title
} else null

fun LibraryState.withDownloadLink(entry: DownloadEntry, localBook: BookRef): LibraryState {
    require(localBook.isLocal)
    val link = DownloadedBookLink(entry.id, localBook, entry.originBook(), entry.originVolumeId())
    return copy(downloadLinks = downloadLinks.filterNot { it.downloadId == entry.id } + link)
}

/** 升级时保留旧分卷的来源身份，避免移出父书后丢失仅存的挂载关联。 */
fun LibraryState.withMigratedDownloadLinks(): LibraryState {
    val migrated = books.mapNotNull { saved ->
        val parent = saved.parentWenkuKey?.let(BookRef::fromKey)?.takeIf { it.isWenku } ?: return@mapNotNull null
        val volume = saved.sourceVolumeId ?: return@mapNotNull null
        if(!saved.book.ref.isLocal || downloadLinks.any { it.localBook == saved.book.ref && it.sourceBook == parent && it.volumeId == volume }) return@mapNotNull null
        DownloadedBookLink("legacy-volume-${saved.book.ref.id}", saved.book.ref, parent, volume)
    }
    return if(migrated.isEmpty()) this else copy(downloadLinks = downloadLinks + migrated)
}

/** 即使下载源文件已按偏好清理，分卷入口仍指向可阅读的本地副本。 */
fun LibraryState.downloadedVolume(source: BookRef, volumeId: String): BookRef? =
    downloadLinks.lastOrNull { it.sourceBook == source && it.volumeId == volumeId }?.localBook
        ?: books.firstOrNull { it.book.ref.isLocal && it.parentWenkuKey == source.key &&
            (it.sourceVolumeId == volumeId || (it.sourceVolumeId == null && it.book.title == volumeId)) }?.book?.ref

data class BookDeletionPlan(val books: Set<BookRef>, val downloadIds: Set<String>, val linkIds: Set<String>)

/** 从整本书或单份下载展开关联；删除单个分卷不会向上扩展到父书及其他卷。 */
fun LibraryState.bookDeletionPlan(refs: Set<BookRef> = emptySet(), downloadIds: Set<String> = emptySet(), includeSourceBooks: Boolean = true): BookDeletionPlan {
    val targets = refs.toMutableSet()
    val ids = downloadIds.toMutableSet()
    val sourceBooks = refs.filterNot { it.isLocal }.toSet() + if(includeSourceBooks) downloadLinks
        .filter { it.localBook in refs || it.downloadId in downloadIds }.mapNotNull { it.sourceBook?.takeUnless { source -> source.isWenku || source.isLocal } }.toSet() else emptySet()
    targets += sourceBooks
    val sourceKeys = sourceBooks.map { it.key }.toSet()
    val mounted = books.filter { it.parentWenkuKey in sourceKeys }
    targets += mounted.map { it.book.ref }
    // 同一分卷的其他格式/译文也属于这个分卷，未导入的下载同样需要清理。
    val volumes = downloadLinks.filter { (it.localBook in refs || it.sourceBook in sourceBooks || it.downloadId in ids) && it.sourceBook?.isWenku == true }
        .map { it.sourceBook to it.volumeId }.toSet() + books.filter { (it.book.ref in refs || it in mounted) && it.parentWenkuKey != null && it.sourceVolumeId != null }
        .map { BookRef.fromKey(it.parentWenkuKey!!) to it.sourceVolumeId } + downloads.filter { it.id in ids && it.originBook()?.isWenku == true }
        .map { it.originBook() to it.originVolumeId() }
    ids += downloads.filter { it.originBook() in sourceBooks || (it.originBook()?.isWenku == true && (it.originBook() to it.originVolumeId()) in volumes) }.map { it.id }
    targets += books.filter { saved -> saved.parentWenkuKey != null &&
        (BookRef.fromKey(saved.parentWenkuKey) to (saved.sourceVolumeId ?: saved.book.title)) in volumes }.map { it.book.ref }
    val links = downloadLinks.filter { it.localBook in refs || it.sourceBook in sourceBooks || it.downloadId in ids ||
        (it.sourceBook?.isWenku == true && (it.sourceBook to it.volumeId) in volumes) }
    targets += links.map { it.localBook }
    // 哈希去重可能让不同作品共用一份副本。删除来源作品时，保留另一作品仍使用的文件。
    val retained = downloadLinks.filter { it !in links && it.localBook !in refs }.map { it.localBook }.toSet()
    targets.removeAll(retained)
    ids += downloadLinks.filter { it.localBook in targets }.map { it.downloadId }
    return BookDeletionPlan(targets, ids.intersect(downloads.map { it.id }.toSet()), links.map { it.downloadId }.toSet())
}

fun LibraryState.withDeletedBookRecords(refs: Set<BookRef>, eraseReadingData: Boolean, linkIds: Set<String> = emptySet()): LibraryState {
    val keys = refs.map { it.key }.toSet()
    val removed = withoutBooks(refs).copy(downloadLinks = downloadLinks.filterNot { it.localBook in refs || it.sourceBook in refs || it.downloadId in linkIds })
    return if(!eraseReadingData) removed else removed.copy(
        positions = positions - keys, readingHistory = readingHistory - keys, notes = notes.filterNot { it.key in keys },
        bookSettings = bookSettings - keys, personalGlossaries = personalGlossaries - keys,
        updateSnapshots = updateSnapshots - keys, bookUpdates = bookUpdates - keys,
    )
}
