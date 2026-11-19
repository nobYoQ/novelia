package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.network.ALL_CLOUD_FAVORITES
import cc.novelia.app.data.network.CloudFavoriteFolderImport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val CLOUD_DEFAULT_ID = "default"

private fun CloudFolders.choices(wenku: Boolean) = (if(wenku) favoredWenku else favoredWeb)
    .filter { it.id.isNotBlank() && it.id != ALL_CLOUD_FAVORITES }

private fun List<Folder>.named(title: String, current: Folder? = null): Folder? =
    current?.takeIf { it.title.trim() == title } ?: firstOrNull { it.title.trim() == title }

internal fun CloudFolders.autoCopyFolder(ref: BookRef, localFolder: String): String =
    choices(ref.isWenku).named(localFolder)?.id ?: CLOUD_DEFAULT_ID

internal enum class FavoriteSyncPriority { Local, Cloud }

internal enum class FavoriteSyncPhase { ReadWeb, ReadWenku, ResolveFolders, Compare, Write }

internal data class FavoriteSyncProgress(val phase: FavoriteSyncPhase, val completed: Int = 0,
    val total: Int? = null, val booksRead: Int = 0) {
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { completed.coerceIn(0, it).toFloat() / it }
}

internal data class FavoriteDestination(val title: String, val cloudId: String? = null, val create: Boolean = false)

internal data class FavoriteSyncChange(val book: BookCard, val from: String?, val target: FavoriteDestination)

internal data class FavoriteSyncPlan(val priority: FavoriteSyncPriority, val changes: List<FavoriteSyncChange>) {
    val additions = changes.count { it.from == null }
    val moves = changes.size - additions

    fun requireSameChanges(current: FavoriteSyncPlan) {
        fun FavoriteSyncPlan.actions() = changes.associate { it.book.ref to (it.from to it.target) }
        require(priority == current.priority && actions() == current.actions()) { "收藏或收藏夹已变化，请重新检查并确认。" }
    }
}

internal fun LibraryState.planFavoriteSync(priority: FavoriteSyncPriority, cloud: CloudFavoriteFolderImport): FavoriteSyncPlan {
    val localBooks = books.filter { it.book.ref.isOnline }.associateBy { it.book.ref }
    val cloudBooks = cloud.books.associateBy { it.ref }
    val choices = mapOf(false to cloud.folders.choices(false), true to cloud.folders.choices(true))
    val localFolders = folders.toSet()
    val changes = when(priority) {
        FavoriteSyncPriority.Local -> localBooks.values.mapNotNull { saved ->
            val ref = saved.book.ref
            val foldersForKind = choices.getValue(ref.isWenku)
            val current = cloudBooks[ref]?.let { book -> foldersForKind.first { it.id == book.favored } }
            val target = foldersForKind.named(saved.folder, current)
                ?: foldersForKind.firstOrNull { saved.folder == DEFAULT_FOLDER && it.id == CLOUD_DEFAULT_ID }
            if(target != null && target.id == current?.id) return@mapNotNull null
            val destination = if(target != null) FavoriteDestination(target.title, target.id)
                else FavoriteDestination(saved.folder, create = true)
            FavoriteSyncChange(saved.book, current?.title, destination)
        }
        FavoriteSyncPriority.Cloud -> cloudBooks.values.mapNotNull { book ->
            val source = choices.getValue(book.ref.isWenku).first { it.id == book.favored }
            val title = source.title.trim()
            val target = when {
                title in localFolders -> title
                source.id == CLOUD_DEFAULT_ID -> DEFAULT_FOLDER
                else -> title.also { require(it.isNotBlank() && it != "全部" && it.none(Char::isISOControl)) {
                    "「${book.title}」的云端收藏夹名称不能用于本地收藏，请先重命名。"
                } }
            }
            val current = localBooks[book.ref]?.folder
            if(current == target) null else FavoriteSyncChange(book, current, FavoriteDestination(target, create = target !in localFolders))
        }
    }
    return FavoriteSyncPlan(priority, changes)
}

internal fun LibraryState.applyCloudPriority(plan: FavoriteSyncPlan): LibraryState {
    require(plan.priority == FavoriteSyncPriority.Cloud)
    if(plan.changes.isEmpty()) return this
    return withGroupedCloudFavorites(plan.changes.map { it.book }, plan.changes.associate { it.book.ref.key to it.target.title })
}

internal suspend fun FavoriteSyncPlan.applyLocalPriority(
    validate: () -> Unit,
    createFolder: suspend (wenku: Boolean, title: String) -> String,
    put: suspend (BookRef, String) -> Boolean,
    onProgress: (processed: Int, queued: Int) -> Unit,
) {
    require(priority == FavoriteSyncPriority.Local)
    val created = mutableMapOf<Pair<Boolean, String>, String>()
    var queued = 0
    changes.forEachIndexed { index, change ->
        currentCoroutineContext().ensureActive()
        validate()
        val ref = change.book.ref
        val folder = change.target.cloudId ?: created.getOrPut(ref.isWenku to change.target.title) {
            createFolder(ref.isWenku, change.target.title).trim().also {
                require(it.isNotBlank() && it != ALL_CLOUD_FAVORITES && '/' !in it) { "云端未返回有效的收藏夹，请重新检查。" }
            }
        }
        validate()
        if(put(ref, folder)) queued++
        onProgress(index + 1, queued)
    }
}
