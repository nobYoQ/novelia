package cc.novelia.app.files.epub

import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.LocalChapter
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import cc.novelia.app.files.DocumentTools

/** 使用请求参数，不从书名、文字脚本或段落奇偶推断双语方向。 */
internal fun DownloadEntry.contentMode(): String? =
    url.toHttpUrlOrNull()?.queryParameter("mode")?.takeIf { it in setOf("zh", "jp", "jp-zh", "zh-jp") }

/** 仅在段落可逐项对应时恢复元数据；采用旧版切段的书仍用原来的阅读和笔记定位。 */
internal fun recoverEpubChapter(file: File, chapter: LocalChapter, downloadMode: String?, checkCancelled: () -> Unit = {}): LocalChapter {
    DocumentTools.requireImportSize(file.length())
    fun read(preserveLegacyBody: Boolean) = readEpubFile(file, imageSink = { _, _ -> }, downloadMode = downloadMode,
        chapterId = chapter.id, preserveLegacyBody = preserveLegacyBody, checkCancelled = checkCancelled).chapters.singleOrNull()?.takeIf { restored ->
        restored.paragraphs.size == chapter.paragraphs.size && restored.paragraphs.indices.all { index ->
            val previous = chapter.paragraphs[index]
            val current = restored.paragraphs[index]
            previous == current || normalizeEpubWhitespace(previous) == current
        }
    }
    val restored = read(false) ?: read(true)
    requireNotNull(restored) { "原 EPUB 正文与已导入内容不一致" }
    return restored.copy(title = chapter.title)
}
