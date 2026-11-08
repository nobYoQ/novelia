package cc.novelia.app.files.importing

import cc.novelia.app.data.model.localDocumentFormats
import cc.novelia.app.data.model.comicMimeTypes
import cc.novelia.app.data.model.supportedBookFiles

/** 扩展名优先；缺失时只根据明确的书籍 MIME 补齐，不能把未知二进制当作 TXT。 */
internal fun documentImportName(displayName: String?, pathName: String?, mimeType: String?): String {
    val name = displayName?.takeIf(String::isNotBlank)
        ?: pathName?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "导入文档"
    val extension = name.substringAfterLast('.', "").lowercase()
    if(extension in localDocumentFormats) return name
    require(extension.isEmpty()) { supportedBookFiles }
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val format = when(mime) {
        "application/epub+zip" -> "epub"
        "text/plain" -> "txt"
        "application/x-subrip", "application/srt", "text/srt" -> "srt"
        else -> comicMimeTypes.entries.firstOrNull { mime in it.value }?.key ?: error("无法识别文件类型，$supportedBookFiles")
    }
    return "$name.$format"
}
