package cc.novelia.app.data.model

import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.catalog.SavedSearchPreset
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.updates.BookUpdateSnapshot
import cc.novelia.app.data.webdav.SyncReplica
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 本机精确阅读位置。index 是含章标题的阅读列表下标（正文从 1 开始），offset 为滚动像素偏移；
 * textOffset 是整段显示文本中的 UTF-16 偏移，用于静态分页及模式切换后的定位。
 * chapterIndex 从 0 开始，章节/段落总数可为空，不能为显示进度额外阻塞正文加载。
 * chapterCompleted 独立记录本章已到末屏，不能用修改屏顶锚点的方式表示读完。
 */
@Serializable data class Position(val chapterId: String, val index: Int = 0, val offset: Int = 0, val title: String = "", val updatedAt: Long = System.currentTimeMillis(), val textOffset: Int = 0,
    val chapterIndex: Int? = null, val chapterCount: Int? = null, val paragraphCount: Int? = null,
    val chapterCompleted: Boolean = false,
    // 跨设备使用原始段落编号；字符偏移仅在相同语言/译源投影下可复用。
    val sourceParagraph: Int? = null, val anchorSource: String? = null,
    val anchorTextHash: String? = null)

/**
 * 本地书架条目，不代表当前账号的云端收藏关系。parentWenkuKey 将本地文件挂到文库父书目，
 * volumeOrder 保存父书目的显式分卷顺序，缺失或失效引用由分卷整理逻辑处理。
 */
@Serializable data class SavedBook(val book: BookCard, val folder: String = "默认收藏", val pinned: Boolean = false, val status: String = "在读", val addedAt: Long = System.currentTimeMillis(), val hasUpdates: Boolean = false,
    val parentWenkuKey: String? = null, val volumesExpanded: Boolean = false, val volumeOrder: List<String> = emptyList(),
    val folderId: String? = null,
    val sourceVolumeId: String? = null, val siteVolumeOrderDescending: Boolean = false)

/** key 为 BookRef.key；text 可为空，此时仍是一条保留章节和段落位置的书签。 */
@Serializable data class Note(val id: String, val key: String, val chapterId: String, val paragraph: Int, val quote: String, val text: String, val createdAt: Long = System.currentTimeMillis(), val bookTitle: String = "", val chapterTitle: String = "", val bookmarked: Boolean = true)

/** 阅读历史是独立的书目摘要；删除历史不会影响续读位置。 */
@Serializable data class ReadingHistoryEntry(val bookKey: String, val bookTitle: String, val chapterId: String,
    val chapterTitle: String, val lastReadAt: Long)

const val DEFAULT_FOLDER = "默认收藏"
const val DEFAULT_FOLDER_ID = "folder-default"

/** 旧设备按名称生成相同 ID，首次同步前无需额外的云端匹配步骤。 */
fun legacyFolderId(name: String): String = if(name == DEFAULT_FOLDER) DEFAULT_FOLDER_ID
    else UUID.nameUUIDFromBytes("novelia:folder:$name".toByteArray(Charsets.UTF_8)).toString()

/**
 * 本机书库和偏好的不可变快照，由 LocalStore 统一发布与保存。
 * positions/bookSettings 等书目映射使用 BookRef.key；syncStatus 以账号名隔离，
 * pending 中的每项也保留所属账号。新增持久字段应提供兼容旧 JSON 的默认值。
 * 会话令牌由 Session 单独管理，章节及文档正文通过专用存储读取。
 */
@Serializable data class LibraryState(
    val books: List<SavedBook> = emptyList(), val folders: List<String> = listOf("默认收藏"),
    val folderIds: Map<String, String> = mapOf(DEFAULT_FOLDER to DEFAULT_FOLDER_ID),
    val positions: Map<String, Position> = emptyMap(), val notes: List<Note> = emptyList(),
    val readingHistory: Map<String, ReadingHistoryEntry> = emptyMap(), val historyMigrated: Boolean = false,
    val downloads: List<DownloadEntry> = emptyList(), val blockedBooks: Set<String> = emptySet(), val blockedTags: Set<String> = emptySet(),
    val downloadLinks: List<DownloadedBookLink> = emptyList(),
    val blockedAuthors: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(), val savedSearches: List<String> = emptyList(),
    val savedSearchPresets: List<SavedSearchPreset> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val reader: ReaderSettings = ReaderSettings(), val bookSettings: Map<String, ReaderSettings> = emptyMap(),
    val theme: String = "system", val reducedMotion: Boolean = false, val historyPaused: Boolean = false, val autoCollapseCloudFilters: Boolean = true,
    val autoSaveCloudFavoritesLocally: Boolean = true,
    // 文件清理仅为本机偏好，默认关闭，普通设置备份会保留这两个选项。
    val deleteDownloadAfterImport: Boolean = false, val deleteLocalCopyOnShelfRemoval: Boolean = false,
    val pending: List<PendingAction> = emptyList(), val updateNotifications: Boolean = false,
    // 本机启动检查偏好，普通设置备份保留，WebDAV 不同步。
    val autoCheckAppUpdates: Boolean = true,
    val blockedUsers: Set<String> = emptySet(), val hideNovelComments: Boolean = false, val wifiOnly: Boolean = false,
    val personalGlossaries: Map<String, Map<String, String>> = emptyMap(),
    val autoSync: Boolean = true, val syncStatus: Map<String, CloudSyncStatus> = emptyMap(),
    val updateSnapshots: Map<String, BookUpdateSnapshot> = emptyMap(),
    val bookUpdates: Map<String, BookUpdateInfo> = emptyMap(),
    val clipboardLinkHints: Boolean = true,
    val keywordLimit: Int? = null,
    // 本机只提示一次，不随单书设置或翻页模式切换重复展示。
    val readerTapTutorialSeen: Boolean = false,
    val syncReplica: SyncReplica = SyncReplica(),
    val forumRulesReminderDismissed: Boolean = false
)

/** 收藏夹名称仅用于显示；改名时 ID 及所有本地分卷仍保持原关系。 */
fun LibraryState.withStableFolderIds(): LibraryState {
    val names = (listOf(DEFAULT_FOLDER) + folders + books.map { it.folder }).distinct()
    val ids = names.associateWith { if(it == DEFAULT_FOLDER) DEFAULT_FOLDER_ID else folderIds[it] ?: legacyFolderId(it) }
    return copy(folders = names, folderIds = ids, books = books.map { it.copy(folderId = ids.getValue(it.folder)) })
}

fun LibraryState.createShelfFolder(name: String): LibraryState {
    require(name.isNotBlank() && name == name.trim() && name != "全部" && name.none(Char::isISOControl)) { "收藏夹名称无效" }
    require(name !in folders) { "收藏夹名称已存在" }
    return withStableFolderIds().let { it.copy(folders = it.folders + name, folderIds = it.folderIds + (name to UUID.randomUUID().toString())) }
}

fun LibraryState.renameShelfFolder(old: String, name: String): LibraryState {
    require(old in folders && old != DEFAULT_FOLDER) { "不能修改默认收藏夹" }
    require(name.isNotBlank() && name == name.trim() && name != "全部" && name.none(Char::isISOControl)) { "收藏夹名称无效" }
    if(old == name) return this
    require(name !in folders) { "收藏夹名称已存在" }
    val state = withStableFolderIds()
    return state.copy(folders = state.folders.map { if(it == old) name else it },
        folderIds = (state.folderIds - old) + (name to state.folderIds.getValue(old)),
        books = state.books.map { if(it.folder == old) it.copy(folder = name) else it })
}

fun LibraryState.deleteShelfFolder(name: String): LibraryState {
    require(name in folders && name != DEFAULT_FOLDER) { "不能删除默认收藏夹" }
    val state = withStableFolderIds()
    return state.copy(folders = state.folders - name, folderIds = state.folderIds - name,
        books = state.books.map { if(it.folder == name) it.copy(folder = DEFAULT_FOLDER, folderId = DEFAULT_FOLDER_ID) else it })
}
