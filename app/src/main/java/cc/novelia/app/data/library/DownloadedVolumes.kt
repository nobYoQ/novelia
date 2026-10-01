package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook

/** 开始阅读下载卷时补齐本地父书目；已有收藏的文件夹、状态和元数据保持不变。 */
fun LibraryState.withDownloadedVolume(volume: BookRef, source: BookCard): LibraryState {
    require(source.ref.isWenku) { "下载分卷必须归属文库小说" }
    val collected = if(books.any { it.book.ref == source.ref }) this
        else copy(books = books + SavedBook(source.copy(favored = null, cloudReading = null)))
    return collected.withVolumeParent(volume.key, source.ref.key)
}
