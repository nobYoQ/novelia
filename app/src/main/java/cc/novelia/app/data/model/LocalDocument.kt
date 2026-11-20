package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

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
    /** EPUB 目录标题独立升级，可覆盖章节正文文件中的旧标题。 */
    val epubTitleVersion: Int = 0,
    /** 原 EPUB 的正式目录顺序，章节正文仍按 chapters 中的 spine 顺序阅读。 */
    val epubNavigationIds: List<String> = emptyList())

internal fun LocalDocument.readingOrderToc(): List<TocItem> = chapters.mapIndexed { index, chapter ->
    TocItem(chapter.title.ifBlank { "第 ${index + 1} 节" }, chapterId = chapter.id)
}

internal fun LocalDocument.navigationToc(readingOrder: List<TocItem> = readingOrderToc()): List<TocItem> {
    if (epubNavigationIds.isEmpty()) return readingOrder
    val byId = readingOrder.associateBy { it.chapterId }
    return epubNavigationIds.mapNotNull(byId::get).ifEmpty { readingOrder }
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
