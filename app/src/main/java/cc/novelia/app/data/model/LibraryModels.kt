package cc.novelia.app.data.model

import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.catalog.SavedSearchPreset
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.updates.BookUpdateSnapshot
import kotlinx.serialization.Serializable

/**
 * 本机精确阅读位置。index 是含章标题的阅读列表下标（正文从 1 开始），offset 为滚动像素偏移；
 * textOffset 是整段显示文本中的 UTF-16 偏移，用于静态分页及模式切换后的定位。
 * chapterIndex 从 0 开始，章节/段落总数可为空，不能为显示进度额外阻塞正文加载。
 * chapterCompleted 独立记录本章已到末屏，不能用修改屏顶锚点的方式表示读完。
 */
@Serializable data class Position(val chapterId: String, val index: Int = 0, val offset: Int = 0, val title: String = "", val updatedAt: Long = System.currentTimeMillis(), val textOffset: Int = 0,
    val chapterIndex: Int? = null, val chapterCount: Int? = null, val paragraphCount: Int? = null,
    val chapterCompleted: Boolean = false)

/**
 * 本地书架条目，不代表当前账号的云端收藏关系。parentWenkuKey 将本地文件挂到文库父书目，
 * volumeOrder 保存父书目的显式分卷顺序，缺失或失效引用由分卷整理逻辑处理。
 */
@Serializable data class SavedBook(val book: BookCard, val folder: String = "默认收藏", val pinned: Boolean = false, val status: String = "在读", val addedAt: Long = System.currentTimeMillis(), val hasUpdates: Boolean = false,
    val parentWenkuKey: String? = null, val volumesExpanded: Boolean = false, val volumeOrder: List<String> = emptyList())

/** key 为 BookRef.key；text 可为空，此时仍是一条保留章节和段落位置的书签。 */
@Serializable data class Note(val id: String, val key: String, val chapterId: String, val paragraph: Int, val quote: String, val text: String, val createdAt: Long = System.currentTimeMillis(), val bookTitle: String = "", val chapterTitle: String = "")

/**
 * 本机书库和偏好的不可变快照，由 LocalStore 统一发布与保存。
 * positions/bookSettings 等书目映射使用 BookRef.key；syncStatus 以账号名隔离，
 * pending 中的每项也保留所属账号。新增持久字段应提供兼容旧 JSON 的默认值。
 * 会话令牌由 Session 单独管理，章节及文档正文通过专用存储读取。
 */
@Serializable data class LibraryState(
    val books: List<SavedBook> = emptyList(), val folders: List<String> = listOf("默认收藏"),
    val positions: Map<String, Position> = emptyMap(), val notes: List<Note> = emptyList(),
    val downloads: List<DownloadEntry> = emptyList(), val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(),
    val blockedAuthors: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(), val savedSearches: List<String> = emptyList(), val savedArticles: List<Article> = emptyList(),
    val savedSearchPresets: List<SavedSearchPreset> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val reader: ReaderSettings = ReaderSettings(), val bookSettings: Map<String, ReaderSettings> = emptyMap(),
    val theme: String = "system", val reducedMotion: Boolean = false, val historyPaused: Boolean = false, val autoCollapseCloudFilters: Boolean = true,
    val autoSaveCloudFavoritesLocally: Boolean = true,
    val pending: List<PendingAction> = emptyList(), val updateNotifications: Boolean = false,
    val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false,
    val personalGlossaries: Map<String, Map<String, String>> = emptyMap(),
    val autoSync: Boolean = true, val syncStatus: Map<String, CloudSyncStatus> = emptyMap(),
    val updateSnapshots: Map<String, BookUpdateSnapshot> = emptyMap(),
    val bookUpdates: Map<String, BookUpdateInfo> = emptyMap(),
    val clipboardLinkHints: Boolean = true,
    val keywordLimit: Int? = null
)
