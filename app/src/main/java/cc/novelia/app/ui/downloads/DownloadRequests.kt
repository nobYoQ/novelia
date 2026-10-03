package cc.novelia.app.ui.downloads

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.network.NoveliaApi
import java.util.UUID

internal fun downloadEntries(
    api: NoveliaApi, book: BookCard, volumes: List<String>, mode: String,
    engine: String, parallel: Boolean, type: String,
): List<DownloadEntry> {
    require(!book.ref.isWenku || volumes.isNotEmpty()) { "请先选择要下载的分卷" }
    val targets: List<String?> = if(book.ref.isWenku) volumes.distinct() else listOf(null)
    val engines = listOf(engine) + listOf("sakura", "gpt", "youdao").filterNot { it == engine }
    return targets.map { volume ->
        val id = UUID.randomUUID().toString()
        val name = (volume ?: "${book.title}.$type").replace(Regex("[\\/\\\\:*?\"<>|]"), "_").takeLast(150)
        val filename = "$mode.$name"
        val url = api.downloadUrl(book.ref, volume, mode, engines, parallel, type, filename)
        DownloadEntry(id, volume ?: book.title, "$id-$filename", url, sourceBook = book.ref, sourceCard = book)
    }
}
