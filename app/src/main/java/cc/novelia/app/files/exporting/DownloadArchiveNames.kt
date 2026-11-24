package cc.novelia.app.files.exporting

import cc.novelia.app.data.library.originBook
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.appJson
import java.text.Normalizer
import java.util.Locale

/** 旧下载可用书架及本机详情缓存补齐作者/插画师；导出不发起网络请求。 */
internal fun downloadArchiveBooks(
    entries: List<DownloadEntry>, savedBooks: List<BookCard>, cachedDetail: (BookRef) -> String?,
): List<BookCard> {
    val refs = entries.mapNotNull { it.originBook() }.distinct()
    return savedBooks.filter { it.ref in refs } + refs.mapNotNull { ref ->
        if(ref.isLocal) return@mapNotNull null
        cachedDetail(ref)?.let { raw ->
            runCatching {
                if(ref.isWenku) appJson.decodeFromString<WenkuDetail>(raw).card(ref)
                else appJson.decodeFromString<WebDetail>(raw).card(ref)
            }.getOrNull()
        }
    }
}

private data class ArchiveWork(val ref: BookRef?, val downloadId: String?)
private fun DownloadEntry.archiveWork() = originBook()?.let { ArchiveWork(it, null) } ?: ArchiveWork(null, id)

/** 身份决定分组，名称只负责展示；无法识别来源的旧下载各自保留，避免误合并。 */
internal fun downloadArchivePaths(entries: List<DownloadEntry>, books: List<BookCard>): Map<String, String> {
    val folderNames = mutableSetOf<String>()
    val knownBooks = books.groupBy { it.ref }
    val folders = entries.groupBy { it.archiveWork() }.mapValues { (work, volumes) ->
        val cards = knownBooks[work.ref].orEmpty() + volumes.mapNotNull { it.sourceCard?.takeIf { card -> card.ref == work.ref } }
        archiveFolder(folderNames, cards, volumes.first().title)
    }
    val fileNames = mutableSetOf<String>()
    return entries.associate { entry ->
        val label = safeArchiveName(entry.fileName.removePrefix("${entry.id}-"), "下载文件")
        val dot = label.lastIndexOf('.').takeIf { it > 0 } ?: label.length
        val folder = folders.getValue(entry.archiveWork())
        entry.id to uniqueArchiveName(fileNames, label.take(dot), label.substring(dot), "$folder/")
    }
}

private fun List<String>.creditNames() = map(String::trim).filter(String::isNotBlank).distinct().joinToString("、")

internal fun archiveFolder(used: MutableSet<String>, cards: List<BookCard>, fallback: String): String {
    val title = cards.firstNotNullOfOrNull { it.title.trim().ifBlank { it.originalTitle.trim() }.takeIf(String::isNotBlank) }
        ?: fallback.trim().ifBlank { "未命名作品" }
    val authors = cards.firstNotNullOfOrNull { it.authors.creditNames().takeIf(String::isNotBlank) }
    val artists = cards.firstNotNullOfOrNull { it.artists }?.creditNames().orEmpty()
    val credit = authors?.let { if(artists.isBlank()) it else "$it×$artists" }
    return uniqueArchiveName(used, safeArchiveName(if(credit == null) title else "[$credit] $title", "未命名作品"))
}

private val unsafeArchiveCharacters = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val reservedArchiveName = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")

internal fun safeArchiveName(value: String, fallback: String): String {
    val name = value.replace(unsafeArchiveCharacters, "_").trim(' ', '.').ifBlank { fallback }
    return if(reservedArchiveName.matches(name)) "_$name" else name
}

internal fun uniqueArchiveName(names: MutableSet<String>, stem: String, extension: String = "", prefix: String = ""): String {
    // 单个路径段留出序号空间，按 UTF-8 字节限制，避免中文长书名解压失败。
    val limit = (240 - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
    var end = minOf(stem.length, limit)
    while(end > 0 && (stem.substring(0, end).toByteArray(Charsets.UTF_8).size > limit ||
            (end < stem.length && Character.isHighSurrogate(stem[end - 1]) && Character.isLowSurrogate(stem[end])))) end--
    val base = stem.take(end).trimEnd(' ', '.').ifBlank { "下载文件" }
    var name = "$prefix$base$extension"
    var duplicate = 2
    while(!names.add(Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT))) {
        name = "$prefix$base（${duplicate++}）$extension"
    }
    return name
}
