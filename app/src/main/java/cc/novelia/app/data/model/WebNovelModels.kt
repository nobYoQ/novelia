package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class WebOutline(
    val providerId: String = "", val novelId: String = "", val titleJp: String = "", val titleZh: String? = null,
    val type: String = "", val attentions: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val extra: String? = null, val favored: String? = null, val lastReadAt: Long? = null,
    val total: Int = 0, val jp: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0, val updateAt: Long? = null
) {
    fun card() = BookCard(BookRef(providerId, novelId), titleZh?.takeIf { it.isNotBlank() } ?: titleJp, titleJp, subtitle = "$type · $total 章", tags = keywords, translated = maxOf(gpt, sakura, youdao), total = total, favored = favored, updateAt = updateAt, translations = mapOf("gpt" to gpt, "sakura" to sakura, "youdao" to youdao))
}

@Serializable data class TocItem(val titleJp: String = "", val titleZh: String? = null, val chapterId: String? = null, val createAt: Long? = null) {
    val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp
}

@Serializable data class WebDetail(
    val wenkuId: String? = null, val titleJp: String = "", val titleZh: String? = null, val authors: List<Author> = emptyList(),
    val type: String = "", val attentions: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val points: Long? = null, val totalCharacters: Long? = null, val introductionJp: String = "", val introductionZh: String? = null,
    val glossary: Map<String, String> = emptyMap(), val toc: List<TocItem> = emptyList(), val visited: Long = 0, val syncAt: Long = 0,
    val favored: String? = null, val lastReadChapterId: String? = null, val jp: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0
) {
    val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp
    fun card(ref: BookRef) = BookCard(ref, title, titleJp, subtitle = authors.joinToString { it.name }, tags = keywords, total = toc.count { it.chapterId != null }, translated = maxOf(gpt, sakura, youdao), favored = favored, translations = mapOf("gpt" to gpt, "sakura" to sakura, "youdao" to youdao))
}

@Serializable data class Chapter(
    val titleJp: String = "", val titleZh: String? = null, val novelTitleJp: String? = null, val novelTitleZh: String? = null,
    val prevId: String? = null, val nextId: String? = null, val paragraphs: List<String> = emptyList(),
    val youdaoParagraphs: List<String>? = null, val gptParagraphs: List<String>? = null, val sakuraParagraphs: List<String>? = null
) { val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp }
