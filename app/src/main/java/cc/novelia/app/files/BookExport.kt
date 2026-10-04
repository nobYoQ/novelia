package cc.novelia.app.files

import cc.novelia.app.data.storage.LocalStore
import java.io.OutputStream

internal fun bookFileMimeType(fileName: String): String = when(fileName.substringAfterLast('.', "").lowercase()) {
    "epub" -> "application/epub+zip"
    "txt" -> "text/plain"
    "srt" -> "application/x-subrip"
    "tsv" -> "text/tab-separated-values"
    else -> "application/octet-stream"
}

internal data class LocalBookExport(val fileName: String, val original: Boolean)

internal fun localBookExportFileName(name: String, sourceFormat: String, format: String): String {
    var stem = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    // 下载分卷名及用户重命名可能自带后缀；也修正旧版导出留下的重复后缀。
    while (stem.substringAfterLast('.', "").lowercase() in setOf(sourceFormat, format)) {
        stem = stem.substringBeforeLast('.').trimEnd()
    }
    return "${stem.take(120).trimEnd().ifBlank { "小说" }}.$format"
}

/** 在打开文件选择器前确定格式，不能把缓存正文作为 EPUB 原件导出。 */
internal fun prepareLocalBookExport(store: LocalStore, id: String): LocalBookExport {
    val doc = store.documentIndex(id)
    val original = store.documentSource(id, doc.format).isFile
    val format = if(original) doc.format else "txt"
    return LocalBookExport(localBookExportFileName(doc.name, doc.format, format), original)
}

/** original 随文件选择器保存；原件在选择期间丢失时明确报错，不改写为另一种格式。 */
internal fun exportLocalBook(store: LocalStore, id: String, original: Boolean,
    destination: () -> OutputStream?, checkCancelled: () -> Unit = {},
) {
    val doc = store.documentIndex(id)
    checkCancelled()
    if(original) {
        val source = store.documentSource(id, doc.format)
        require(source.isFile) { "原文件已不存在，请重新选择导出缓存正文为 TXT" }
        source.inputStream().use { input ->
            requireNotNull(destination()) { "无法写入文件" }.use { output ->
                val buffer = ByteArray(65536)
                while(true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if(count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    } else {
        requireNotNull(destination()) { "无法写入文件" }.bufferedWriter(Charsets.UTF_8).use { writer ->
            doc.chapters.forEachIndexed { index, metadata ->
                checkCancelled()
                val chapter = store.documentChapter(id, metadata.id)
                if(index > 0) writer.write("\n\n")
                writer.write(chapter.title)
                chapter.paragraphs.filterNot { it.startsWith("novelia-image:") }.forEach { text ->
                    checkCancelled()
                    writer.write("\n\n")
                    writer.write(text)
                }
            }
        }
    }
}
