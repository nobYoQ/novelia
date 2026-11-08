package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

internal val comicMimeTypes = mapOf(
    "cbz" to listOf("application/vnd.comicbook+zip", "application/x-cbz"),
    "zip" to listOf("application/zip", "application/x-zip-compressed", "application/x-zip"),
    "cbr" to listOf("application/vnd.comicbook-rar", "application/x-cbr"),
    "rar" to listOf("application/vnd.rar", "application/x-rar-compressed", "application/x-rar"),
    "cb7" to listOf("application/x-cb7"), "7z" to listOf("application/x-7z-compressed"),
    "cbt" to listOf("application/x-cbt"), "tar" to listOf("application/x-tar"),
)
internal val comicArchiveFormats = comicMimeTypes.keys
internal val localDocumentFormats = setOf("epub", "txt", "srt") + comicArchiveFormats
internal const val supportedBookFiles = "支持 EPUB、TXT、SRT、ZIP/CBZ、RAR/CBR、7z/CB7 和 TAR/CBT 文件"

enum class ComicReadingMode { RightToLeft, LeftToRight, Webtoon }

/** 保留 EPUB 的原始段落下标；一个 p 内的 br 可以对应多段。 */
@Serializable data class LocalBilingualGroup(val original: List<Int>, val translations: List<List<Int>>)

@Serializable data class LocalReadingContent(
    val groups: List<LocalBilingualGroup> = emptyList(),
    val secondary: List<Int> = emptyList(),
    val inline: Map<Int, List<InlineContent>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
)

@Serializable data class LocalChapter(val id: String, val title: String, val paragraphs: List<String>,
    val readingContent: LocalReadingContent = LocalReadingContent(),
    val epubContentVersion: Int = 0,
    val downloadMode: String? = null)

/** 本地正文只有一份，不能伪装成在线章节的某个引擎译文。 */
fun LocalChapter.toReaderChapter(novelTitle: String? = null, prevId: String? = null, nextId: String? = null) = Chapter(
    titleJp = title, titleZh = title, novelTitleJp = novelTitle, novelTitleZh = novelTitle,
    prevId = prevId, nextId = nextId, paragraphs = paragraphs, localContent = readingContent)

/**
 * 本地文档既可表示完整的导入/导出内容，也可表示阅读器使用的轻量目录。
 * chapterFiles 非空时，chapters 主要承载标题和顺序，正文按哈希索引延迟读取。
 * images 在可移植格式中保存 Base64 内容，落盘后由独立图片文件承担，不应长期装入内存。
 */
@Serializable data class LocalDocument(val id: String, val name: String, val format: String, val chapters: List<LocalChapter>, val importedAt: Long = System.currentTimeMillis(), val images: Map<String, String> = emptyMap(), val coverImage: String? = null, val sourceHash: String = "",
    /** 以正文哈希寻址的章节文件索引；可移植格式和旧版完整文档中为空。 */
    val chapterFiles: Map<String, String> = emptyMap(),
    /** 下载请求的内容模式，用于结合 EPUB 原文标记识别对照段落。 */
    val downloadMode: String? = null,
    /** 仅 EPUB 导入时预判；旧书在打开时根据章节内容识别。 */
    val imageOnlyEpub: Boolean? = null)

private val imageParagraph = Regex("novelia-image:[a-f0-9]{64}")
internal fun LocalChapter.hasOnlyImagePages() = paragraphs.isNotEmpty() && paragraphs.all(imageParagraph::matches)
