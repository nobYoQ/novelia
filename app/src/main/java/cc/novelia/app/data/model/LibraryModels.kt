package cc.novelia.app.data.model

import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.updates.BookUpdateSnapshot
import kotlinx.serialization.Serializable

@Serializable data class Position(val chapterId: String, val index: Int = 0, val offset: Int = 0, val title: String = "", val updatedAt: Long = System.currentTimeMillis(), val textOffset: Int = 0)

@Serializable data class SavedBook(val book: BookCard, val folder: String = "默认收藏", val pinned: Boolean = false, val status: String = "在读", val addedAt: Long = System.currentTimeMillis(), val hasUpdates: Boolean = false,
    val parentWenkuKey: String? = null, val volumesExpanded: Boolean = false, val volumeOrder: List<String> = emptyList())

@Serializable data class Note(val id: String, val key: String, val chapterId: String, val paragraph: Int, val quote: String, val text: String, val createdAt: Long = System.currentTimeMillis(), val bookTitle: String = "", val chapterTitle: String = "")

@Serializable data class LibraryState(
    val books: List<SavedBook> = emptyList(), val folders: List<String> = listOf("默认收藏"),
    val positions: Map<String, Position> = emptyMap(), val notes: List<Note> = emptyList(),
    val downloads: List<DownloadEntry> = emptyList(), val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(), val savedSearches: List<String> = emptyList(), val savedArticles: List<Article> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val reader: ReaderSettings = ReaderSettings(), val bookSettings: Map<String, ReaderSettings> = emptyMap(),
    val theme: String = "system", val reducedMotion: Boolean = false, val historyPaused: Boolean = false, val autoCollapseCloudFilters: Boolean = true,
    val pending: List<PendingAction> = emptyList(), val updateNotifications: Boolean = false,
    val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false,
    val personalGlossaries: Map<String, Map<String, String>> = emptyMap(),
    val autoSync: Boolean = true, val syncStatus: Map<String, CloudSyncStatus> = emptyMap(),
    val updateSnapshots: Map<String, BookUpdateSnapshot> = emptyMap(),
    val bookUpdates: Map<String, BookUpdateInfo> = emptyMap()
)
