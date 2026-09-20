package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Author(val name: String = "", val link: String? = null)

@Serializable data class BookRef(val provider: String, val id: String) {
    val key: String get() = "$provider/$id"
    val isWenku get() = provider == "wenku"
    val isLocal get() = provider == "local"
    val url get() = if (isWenku) "https://n.novelia.cc/wenku/$id" else "https://n.novelia.cc/novel/$key"
    companion object { fun fromKey(key: String): BookRef = key.split('/', limit = 2).let { BookRef(it[0], it.getOrElse(1) { "" }) } }
}

@Serializable data class BookCard(
    val ref: BookRef, val title: String, val originalTitle: String = "", val cover: String? = null,
    val subtitle: String = "", val tags: List<String> = emptyList(), val translated: Int = 0, val total: Int = 0,
    val favored: String? = null, val updateAt: Long? = null,
    val translations: Map<String, Int> = emptyMap(), val volumeIds: List<String> = emptyList()
)
