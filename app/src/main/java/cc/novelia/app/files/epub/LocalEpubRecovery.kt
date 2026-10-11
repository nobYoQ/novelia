package cc.novelia.app.files.epub

import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.LocalChapter
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import cc.novelia.app.files.DocumentTools

/** 使用请求参数，不从书名、文字脚本或段落奇偶推断双语方向。 */
internal fun DownloadEntry.contentMode(): String? =
    url.toHttpUrlOrNull()?.queryParameter("mode")?.takeIf { it in setOf("zh", "jp", "jp-zh", "zh-jp") }

/** 旧缓存逐章恢复样式和 HTML 空白；只允许同一下标的正文按当前空白规则折叠。 */
internal fun recoverEpubChapter(file: File, chapter: LocalChapter, downloadMode: String?): LocalChapter {
    DocumentTools.requireImportSize(file.length())
    val restored = readEpubFile(file, imageSink = { _, _ -> }, downloadMode = downloadMode, chapterId = chapter.id)
        .chapters.singleOrNull() ?: error("原 EPUB 中没有对应章节")
    require(restored.paragraphs.size == chapter.paragraphs.size && restored.paragraphs.indices.all { index ->
        val previous = chapter.paragraphs[index]
        val current = restored.paragraphs[index]
        previous == current || normalizeEpubWhitespace(previous) == current
    }) { "原 EPUB 正文与已导入内容不一致" }
    return restored.copy(title = chapter.title)
}
