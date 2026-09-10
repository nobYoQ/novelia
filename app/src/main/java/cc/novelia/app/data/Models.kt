package cc.novelia.app.data

import kotlinx.serialization.Serializable

@Serializable data class Page<T>(val pageNumber: Int = 0, val items: List<T> = emptyList())
@Serializable data class Author(val name: String = "", val link: String? = null)
@Serializable data class User(val username: String = "")
@Serializable data class BookRef(val provider: String, val id: String) {
    val key: String get() = "$provider/$id"
    val isWenku get() = provider == "wenku"
    val isLocal get() = provider == "local"
    val url get() = if (isWenku) "https://n.novelia.cc/wenku/$id" else "https://n.novelia.cc/novel/$key"
    companion object { fun fromKey(key: String): BookRef = key.split('/', limit = 2).let { BookRef(it[0], it.getOrElse(1) { "" }) } }
}
@Serializable data class WebOutline(
    val providerId: String = "", val novelId: String = "", val titleJp: String = "", val titleZh: String? = null,
    val type: String = "", val attentions: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val extra: String? = null, val favored: String? = null, val lastReadAt: Long? = null,
    val total: Int = 0, val jp: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0, val updateAt: Long? = null
) {
    fun card() = BookCard(BookRef(providerId, novelId), titleZh?.takeIf { it.isNotBlank() } ?: titleJp, titleJp, subtitle = "$type · $total 章", tags = keywords, translated = maxOf(gpt, sakura, youdao), total = total, favored = favored, updateAt = updateAt)
}
@Serializable data class WenkuOutline(val id: String = "", val title: String = "", val titleZh: String = "", val cover: String? = null, val favored: String? = null) {
    fun card() = BookCard(BookRef("wenku", id), titleZh.ifBlank { title }, title, cover, "文库小说", favored = favored)
}
@Serializable data class BookCard(
    val ref: BookRef, val title: String, val originalTitle: String = "", val cover: String? = null,
    val subtitle: String = "", val tags: List<String> = emptyList(), val translated: Int = 0, val total: Int = 0,
    val favored: String? = null, val updateAt: Long? = null
)
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
    fun card(ref: BookRef) = BookCard(ref, title, titleJp, subtitle = authors.joinToString { it.name }, tags = keywords, total = toc.count { it.chapterId != null }, translated = maxOf(gpt, sakura, youdao), favored = favored)
}
@Serializable data class Chapter(
    val titleJp: String = "", val titleZh: String? = null, val novelTitleJp: String? = null, val novelTitleZh: String? = null,
    val prevId: String? = null, val nextId: String? = null, val paragraphs: List<String> = emptyList(),
    val youdaoParagraphs: List<String>? = null, val gptParagraphs: List<String>? = null, val sakuraParagraphs: List<String>? = null
) { val title get() = titleZh?.takeIf { it.isNotBlank() } ?: titleJp }
@Serializable data class WenkuVolume(val asin: String = "", val title: String = "", val titleZh: String? = null, val cover: String? = null, val coverHires: String? = null, val publisher: String? = null, val imprint: String? = null, val publishAt: Long? = null)
@Serializable data class JapaneseVolume(val volumeId: String = "", val total: Int = 0, val youdao: Int = 0, val gpt: Int = 0, val sakura: Int = 0)
@Serializable data class WenkuDetail(
    val title: String = "", val titleZh: String = "", val cover: String? = null,
    val authors: List<String> = emptyList(), val artists: List<String> = emptyList(), val keywords: List<String> = emptyList(),
    val publisher: String? = null, val imprint: String? = null, val latestPublishAt: Long? = null,
    val level: String = "一般向", val introduction: String = "", val webIds: List<String> = emptyList(),
    val volumes: List<WenkuVolume> = emptyList(), val glossary: Map<String, String> = emptyMap(), val visited: Long = 0,
    val favored: String? = null, val volumeZh: List<String> = emptyList(), val volumeJp: List<JapaneseVolume> = emptyList()
) { fun card(ref: BookRef) = BookCard(ref, titleZh.ifBlank { title }, title, cover, authors.joinToString(), keywords, total = volumeJp.size + volumeZh.size, favored = favored) }
@Serializable data class Folder(val id: String = "", val title: String = "")
@Serializable data class CloudFolders(val favoredWeb: List<Folder> = emptyList(), val favoredWenku: List<Folder> = emptyList())
@Serializable data class Article(
    val id: String = "", val title: String = "", val content: String = "", val category: String = "General",
    val locked: Boolean = false, val pinned: Boolean = false, val hidden: Boolean = false, val numViews: Int = 0, val numComments: Int = 0,
    val user: User = User(), val createAt: Long = 0, val updateAt: Long = 0
)
@Serializable data class Comment(
    val id: String = "", val user: User = User(), val content: String = "", val hidden: Boolean = false,
    val createAt: Long = 0, val numReplies: Int = 0, val replies: List<Comment> = emptyList()
)
@Serializable data class Profile(val username: String, val role: String, val createdAt: Long, val expiresAt: Long) {
    val canPost get() = role in listOf("admin", "member")
    val canEdit get() = canPost && (role == "admin" || System.currentTimeMillis() / 1000 - createdAt >= 30L * 86400)
}
@Serializable data class ReaderSettings(
    val mode: String = "zh", val engines: List<String> = listOf("sakura", "gpt", "youdao"), val parallel: Boolean = false,
    val fontSize: Float = 19f, val lineHeight: Float = 1.8f, val weight: Boolean = false, val width: Float = 720f,
    val indent: Boolean = true, val theme: String = "system", val secondaryAlpha: Float = .65f, val underline: Boolean = false,
    val keepScreenOn: Boolean = false, val volumeKeys: Boolean = false, val paged: Boolean = false,
    val brightness: Float = -1f, val speechRate: Float = 1f, val speechMinutes: Int = 30, val traditional: Boolean = false, val speechLanguage: String = "auto"
)
@Serializable data class Position(val chapterId: String, val index: Int = 0, val offset: Int = 0, val title: String = "", val updatedAt: Long = System.currentTimeMillis())
@Serializable data class SavedBook(val book: BookCard, val folder: String = "默认收藏", val pinned: Boolean = false, val status: String = "在读", val addedAt: Long = System.currentTimeMillis(), val hasUpdates: Boolean = false)
@Serializable data class Note(val id: String, val key: String, val chapterId: String, val paragraph: Int, val quote: String, val text: String, val createdAt: Long = System.currentTimeMillis())
@Serializable data class LocalChapter(val id: String, val title: String, val paragraphs: List<String>)
@Serializable data class LocalDocument(val id: String, val name: String, val format: String, val chapters: List<LocalChapter>, val importedAt: Long = System.currentTimeMillis(), val images: Map<String, String> = emptyMap(), val coverImage: String? = null, val sourceHash: String = "")
@Serializable data class DownloadEntry(val id: String, val title: String, val fileName: String, val url: String, val status: String = "等待下载", val progress: Int = 0, val error: String? = null)
@Serializable data class PendingAction(val id: String, val account: String, val method: String, val path: String, val body: String? = null, val contentType: String = "application/json")
@Serializable data class LibraryState(
    val books: List<SavedBook> = emptyList(), val folders: List<String> = listOf("默认收藏"),
    val positions: Map<String, Position> = emptyMap(), val notes: List<Note> = emptyList(),
    val downloads: List<DownloadEntry> = emptyList(), val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(), val savedSearches: List<String> = emptyList(), val savedArticles: List<Article> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val reader: ReaderSettings = ReaderSettings(), val bookSettings: Map<String, ReaderSettings> = emptyMap(),
    val theme: String = "system", val reducedMotion: Boolean = false, val historyPaused: Boolean = false,
    val pending: List<PendingAction> = emptyList(), val updateNotifications: Boolean = false,
    val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false,
    val personalGlossaries: Map<String, Map<String, String>> = emptyMap()
)
@Serializable data class SettingsBackup(val version: Int = 1, val reader: ReaderSettings = ReaderSettings(), val theme: String = "system", val reducedMotion: Boolean = false, val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(), val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false)

val providers = linkedMapOf("kakuyomu" to "Kakuyomu", "syosetu" to "成为小说家吧", "novelup" to "Novelup", "hameln" to "Hameln", "pixiv" to "Pixiv", "alphapolis" to "Alphapolis")
