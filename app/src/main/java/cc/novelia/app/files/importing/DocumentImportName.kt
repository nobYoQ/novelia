package cc.novelia.app.files.importing

import cc.novelia.app.data.model.localDocumentFormats

/** 扩展名优先；缺失时只根据明确的书籍 MIME 补齐，不能把未知二进制当作 TXT。 */
internal fun documentImportName(displayName: String?, pathName: String?, mimeType: String?): String {
    val name = displayName?.takeIf(String::isNotBlank)
        ?: pathName?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "导入文档"
    val extension = name.substringAfterLast('.', "").lowercase()
    if(extension in localDocumentFormats) return name
    require(extension.isEmpty()) { "支持 EPUB、TXT、SRT 和 CBZ 文件" }
    val format = when(mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
        "application/epub+zip" -> "epub"
        "text/plain" -> "txt"
        "application/x-subrip", "application/srt", "text/srt" -> "srt"
        else -> error("无法识别文件类型，请分享 EPUB、TXT、SRT 或 CBZ 文件")
    }
    return "$name.$format"
}
