package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class WenkuOutline(val id: String = "", val title: String = "", val titleZh: String = "", val cover: String? = null, val favored: String? = null) {
    fun card() = BookCard(BookRef("wenku", id), titleZh.ifBlank { title }, title, cover, "文库小说", favored = favored)
}

@Serializable data class WenkuVolume(val asin: String = "", val title: String = "", val titleZh: String? = null, val cover: String? = null, val coverHires: String? = null, val publisher: String? = null, val imprint: String? = null, val publishAt: Long? = null)

@Serializable data class JapaneseVolume(val volumeId: String = "", val total: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0)

@Serializable data class WenkuDetail(
    val title: String = "", val titleZh: String = "", val cover: String? = null,
    val authors: List<String> = emptyList(), val artists: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val publisher: String? = null, val imprint: String? = null, val latestPublishAt: Long? = null,
    val level: String = "一般向", val introduction: String = "", val webIds: List<String> = emptyList(),
    val volumes: List<WenkuVolume> = emptyList(), val glossary: Map<String, String> = emptyMap(), val visited: Long = 0,
    val favored: String? = null, val volumeZh: List<String> = emptyList(), val volumeJp: List<JapaneseVolume> = emptyList()
) { fun card(ref: BookRef) = BookCard(ref, titleZh.ifBlank { title }, title, cover, authors.joinToString(), keywords, total = volumeJp.size + volumeZh.size, favored = favored, authors = authors,
    translations = mapOf("gpt" to volumeJp.sumOf { it.gpt }, "sakura" to volumeJp.sumOf { it.sakura }, "youdao" to volumeJp.sumOf { it.youdao }),
    volumeIds = volumeJp.map { "jp:${it.volumeId}" } + volumeZh.map { "zh:$it" },
    publishedVolumeCount = volumes.size) }
