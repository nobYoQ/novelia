package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class WebOutline(
    val providerId: String = "", val novelId: String = "", val titleJp: String = "", val titleZh: String? = null,
    val type: String = "", val attentions: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val extra: String? = null, val favored: String? = null, val lastReadAt: Long? = null,
    val total: Int = 0, val jp: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0, val updateAt: Long? = null
) {
    fun card() = BookCard(BookRef(providerId, novelId), titleZh?.takeIf { it.isNotBlank() } ?: titleJp, titleJp, subtitle = "$type · $total 章", tags = keywords, translated = maxOf(gpt, sakura, youdao), total = total, favored = favored, updateAt = updateAt, translations = mapOf("gpt" to gpt, "sakura" to sakura, "youdao" to youdao), novelType = type, attentions = attentions)
    // 收藏响应不提供阅读历史，即使账号读过该书；缺失表示未知，不能当作未读。
    fun card(account: String?) = card().copy(cloudReading = account?.takeIf { (lastReadAt ?: 0) > 0 }
        ?.let { CloudReadingProgress(it, lastReadAt = lastReadAt) })
}

@Serializable data class TocItem(val titleJp: String = "", val titleZh: String? = null, val chapterId: String? = null, val createAt: Long? = null) {
    val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp
}

@Serializable data class WebDetail(
    val wenkuId: String? = null, val titleJp: String = "", val titleZh: String? = null, val authors: List<Author> = emptyList(),
    val type: String = "", val attentions: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val points: Long? = null, val totalCharacters: Long? = null, val introductionJp: String = "", val introductionZh: String? = null,
    val glossary: Map<String, String> = emptyMap(), val toc: List<TocItem> = emptyList(), val visited: Long = 0, val syncAt: Long = 0,
    val favored: String? = null, val lastReadChapterId: String? = null, val jp: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0,
    val updateAt: Long? = null
) {
    val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp
    // 原站详情接口根据章节发布时间推导书籍更新时间。
    val lastUpdatedAt get() = updateAt?.takeIf { it > 0 } ?: toc.asSequence()
        .filter { it.chapterId != null }.mapNotNull { it.createAt?.takeIf { time -> time > 0 } }.maxOrNull()
    val lastUpdatedChapter get() = toc.withIndex().filter { it.value.chapterId != null }
        .maxWithOrNull(compareBy<IndexedValue<TocItem>> { it.value.createAt?.takeIf { time -> time > 0 } ?: 0L }
            .thenBy { it.index })?.value
    fun card(ref: BookRef) = BookCard(ref, title, titleJp, subtitle = authors.joinToString { it.name }, tags = keywords, total = toc.count { it.chapterId != null }, translated = maxOf(gpt, sakura, youdao), favored = favored, updateAt = lastUpdatedAt, translations = mapOf("gpt" to gpt, "sakura" to sakura, "youdao" to youdao), authors = authors.map { it.name }, novelType = type, attentions = attentions, totalCharacters = totalCharacters?.takeIf { it >= 0 })
    fun card(ref: BookRef, account: String?): BookCard {
        val chapters = toc.mapNotNull { it.chapterId }
        return card(ref).copy(cloudReading = account?.let {
            CloudReadingProgress(it, chapterId = lastReadChapterId,
                chapterIndex = chapters.indexOf(lastReadChapterId).takeIf { index -> index >= 0 }, chapterCount = chapters.size, chapterResolved = true)
        })
    }
}

@Serializable data class Chapter(
    val titleJp: String = "", val titleZh: String? = null, val novelTitleJp: String? = null, val novelTitleZh: String? = null,
    val prevId: String? = null, val nextId: String? = null, val paragraphs: List<String> = emptyList(),
    val youdaoParagraphs: List<String>? = null, val gptParagraphs: List<String>? = null, val sakuraParagraphs: List<String>? = null,
    val localContent: LocalReadingContent? = null
) { val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp }
