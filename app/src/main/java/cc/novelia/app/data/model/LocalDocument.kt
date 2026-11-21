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

@Serializable data class EpubLinkTarget(val chapterId: String, val fragment: String? = null)

/** 按原书顺序保存的目录项；没有跳转目标的条目是分组标题。 */
@Serializable data class EpubNavigationEntry(val title: String, val target: EpubLinkTarget?, val depth: Int)

@Serializable data class LocalReadingContent(
    val groups: List<LocalBilingualGroup> = emptyList(),
    val secondary: List<Int> = emptyList(),
    val inline: Map<Int, List<InlineContent>> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
    val links: Map<String, EpubLinkTarget> = emptyMap(),
    val anchors: Map<String, Int> = emptyMap(),
    val imageDescriptions: Map<Int, String> = emptyMap(),
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
    val imageOnlyEpub: Boolean? = null,
    /** EPUB 目录标题独立升级，可覆盖章节正文文件中的旧标题。 */
    val epubTitleVersion: Int = 0,
    /** 旧索引只记录有目录标题的章节 ID。 */
    val epubNavigationIds: List<String> = emptyList(),
    val epubNavigation: List<EpubNavigationEntry> = emptyList())

internal fun LocalDocument.readingOrderToc(): List<TocItem> = chapters.mapIndexed { index, chapter ->
    TocItem(chapter.title.ifBlank { "第 ${index + 1} 节" }, chapterId = chapter.id)
}

internal fun LocalDocument.navigationToc(readingOrder: List<TocItem> = readingOrderToc()): List<TocItem> {
    if (epubNavigation.isEmpty() && epubNavigationIds.isEmpty()) return readingOrder
    val byId = readingOrder.associateBy { it.chapterId }
    if (epubNavigation.isNotEmpty()) {
        val entries = epubNavigation.mapNotNull { entry ->
            val target = entry.target
            if (target == null || target.chapterId in byId) {
                TocItem(entry.title, chapterId = target?.chapterId, depth = entry.depth, epubFragment = target?.let { it.fragment.orEmpty() })
            } else null
        }
        if (entries.any { it.chapterId != null }) return entries
    }
    if (epubNavigationIds.isEmpty()) return readingOrder
    return epubNavigationIds.mapNotNull(byId::get).ifEmpty { readingOrder }
}

/** 倒序只反转同级条目，分组标题仍在子项之前。 */
internal fun List<IndexedValue<TocItem>>.reverseTocSiblings(): List<IndexedValue<TocItem>> {
    if (none { it.value.depth > 0 }) return asReversed()
    val children = mutableMapOf<Int, MutableList<IndexedValue<TocItem>>>()
    val ancestors = ArrayDeque<IndexedValue<TocItem>>()
    for (item in this) {
        while (ancestors.lastOrNull()?.value?.depth?.let { it >= item.value.depth } == true) ancestors.removeLast()
        children.getOrPut(ancestors.lastOrNull()?.index ?: -1) { mutableListOf() }.add(item)
        ancestors.addLast(item)
    }
    return buildList {
        val pending = ArrayDeque(children[-1].orEmpty())
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            add(item)
            pending.addAll(children[item.index].orEmpty())
        }
    }
}

internal fun LocalDocument.navigationWithUnlisted(readingOrder: List<TocItem>, navigation: List<TocItem>): List<TocItem> {
    if (epubNavigation.isEmpty() || readingOrder === navigation) return readingOrder
    val lastListedIndex = navigation.withIndex().filter { it.value.chapterId != null }.associate { it.value.chapterId to it.index }
    val unlistedByPrecedingEntry = mutableMapOf<Int, MutableList<TocItem>>()
    var preceding = -1
    for (item in readingOrder) {
        val index = lastListedIndex[item.chapterId]
        if (index != null) preceding = index
        else unlistedByPrecedingEntry.getOrPut(preceding) { mutableListOf() }.add(item)
    }
    if (unlistedByPrecedingEntry.isEmpty()) return navigation
    return buildList {
        addAll(unlistedByPrecedingEntry[-1].orEmpty())
        navigation.forEachIndexed { index, item ->
            add(item)
            unlistedByPrecedingEntry[index]?.forEach { add(it.copy(depth = item.depth)) }
        }
    }
}

/** 正在阅读没有单列目录项的正文时，定位到前一个正式章节。 */
internal fun navigationChapterId(readingOrder: List<TocItem>, navigation: List<TocItem>, current: String?): String? {
    if (current == null || readingOrder === navigation) return current
    val listedIds = navigation.mapNotNullTo(hashSetOf()) { it.chapterId }
    var previous: String? = null
    for (item in readingOrder) {
        val id = item.chapterId ?: continue
        if (id in listedIds) previous = id
        if (id == current) return previous
    }
    return null
}
private val imageParagraph = Regex("novelia-image:[a-f0-9]{64}")
internal fun LocalChapter.hasOnlyImagePages() = paragraphs.isNotEmpty() && paragraphs.all(imageParagraph::matches)

internal fun LocalDocument.bookMedium(readChapter: (String) -> LocalChapter): BookMedium = when {
    format in comicArchiveFormats -> BookMedium.Comic
    format == "epub" && (imageOnlyEpub ?: (chapters.isNotEmpty() && chapters.all { readChapter(it.id).hasOnlyImagePages() })) -> BookMedium.Comic
    else -> BookMedium.Novel
}
