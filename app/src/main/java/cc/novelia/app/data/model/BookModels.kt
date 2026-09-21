package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Author(val name: String = "", val link: String? = null)

/**
 * 账号所属的云端阅读摘要，与本机精确段落/字符位置独立。
 * chapterResolved 表示章节标识已经过目录解析；更换账号时不可沿用旧账号的阅读摘要。
 */
@Serializable data class CloudReadingProgress(
    val account: String, val lastReadAt: Long? = null, val chapterId: String? = null,
    val chapterIndex: Int? = null, val chapterCount: Int? = null,
    val chapterResolved: Boolean = false,
) {
    val hasHistory get() = (lastReadAt ?: 0) > 0 || !chapterId.isNullOrBlank()
}

/** 书目的稳定身份；key 用于状态映射和缓存，标题变化或列表排序不应改变它。 */
@Serializable data class BookRef(val provider: String, val id: String) {
    val key: String get() = "$provider/$id"
    val isWenku get() = provider == "wenku"
    val isLocal get() = provider == "local"
    val url get() = if (isWenku) "https://n.novelia.cc/wenku/$id" else "https://n.novelia.cc/novel/$key"
    companion object { fun fromKey(key: String): BookRef = key.split('/', limit = 2).let { BookRef(it[0], it.getOrElse(1) { "" }) } }
}

/** 列表与书架共用的轻量摘要，不包含章节正文；云端阅读摘要带有独立的账号归属。 */
@Serializable data class BookCard(
    val ref: BookRef, val title: String, val originalTitle: String = "", val cover: String? = null,
    val subtitle: String = "", val tags: List<String> = emptyList(), val translated: Int = 0, val total: Int = 0,
    val favored: String? = null, val updateAt: Long? = null,
    val translations: Map<String, Int> = emptyMap(), val volumeIds: List<String> = emptyList(),
    val cloudReading: CloudReadingProgress? = null
)
