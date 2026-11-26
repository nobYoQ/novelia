package cc.novelia.app.ui.downloads

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.files.downloads.downloadFileName
import java.util.UUID

internal fun downloadEntries(
    api: NoveliaApi, book: BookCard, volumes: List<String>, mode: String,
    engine: String, parallel: Boolean, type: String,
): List<DownloadEntry> {
    require(!book.ref.isWenku || volumes.isNotEmpty()) { "请先选择要下载的分卷" }
    val targets: List<String?> = if(book.ref.isWenku) volumes.distinct() else listOf(null)
    val engines = listOf(engine) + listOf("sakura", "gpt", "youdao").filterNot { it == engine }
    return targets.map { volume ->
        val name = volume ?: "${book.title}.$type"
        bookDownloadEntry(api, book, volume, mode, engines, parallel, type, "$mode.$name")
    }
}

internal fun bookDownloadEntry(api: NoveliaApi, book: BookCard, volume: String?, mode: String,
    engines: List<String>, parallel: Boolean, type: String, label: String): DownloadEntry {
    val id = UUID.randomUUID().toString()
    val name = downloadFileName(id, label)
    val url = api.downloadUrl(book.ref, volume, mode, engines, parallel, type, name.removePrefix("$id-"))
    return DownloadEntry(id, volume ?: book.title, name, url, sourceBook = book.ref, sourceCard = book)
}
