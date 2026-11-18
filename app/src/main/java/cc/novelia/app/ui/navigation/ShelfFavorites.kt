package cc.novelia.app.ui.navigation

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.library.CloudCopyResult
import cc.novelia.app.data.library.FavoriteSyncPhase
import cc.novelia.app.data.library.FavoriteSyncPlan
import cc.novelia.app.data.library.FavoriteSyncPriority
import cc.novelia.app.data.library.FavoriteSyncProgress
import cc.novelia.app.data.library.applyCloudPriority
import cc.novelia.app.data.library.applyLocalPriority
import cc.novelia.app.data.library.copyLocalFavoriteToCloud
import cc.novelia.app.data.library.planFavoriteSync
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.data.network.CloudFavoriteFolderImport
import cc.novelia.app.data.network.allCloudFavorites
import cc.novelia.app.data.network.cloudFavoriteCollection
import cc.novelia.app.data.network.cloudFavoritePath
import cc.novelia.app.data.network.resolveCloudFavoriteFolders
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.updates.withSavedBook
import cc.novelia.app.ui.components.base.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

internal fun AppController.addLocalFavorite(book: BookCard, folder: String) {
    val binding = session.capture()
    action {
        require(store.recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复。" }
        store.update { state ->
            (if(folder in state.folders) state else state.createShelfFolder(folder)).withSavedBook(book, folder)
        }
        val saved = "已加入本地「$folder」"
        try {
            val result = store.state.value.copyLocalFavoriteToCloud(book.ref, folder, binding.account, currentFolder = {
                session.ensureCurrent(binding)
                if(book.ref.isWenku) wenkuDetail(book.ref).favored else webDetail(book.ref).favored
            }, cloudFolders = { session.ensureCurrent(binding); folders() }, save = { target ->
                cloudMutation("PUT", book.ref.cloudFavoritePath(target), notifyQueued = false, binding = binding)
            })
            message(when(result) {
                CloudCopyResult.LocalOnly -> saved
                CloudCopyResult.LoginRequired -> "$saved；登录后可在书架同步到云端"
                CloudCopyResult.AlreadySaved -> "$saved；已保留原云端收藏"
                CloudCopyResult.Queued -> "$saved；云端收藏待同步"
                CloudCopyResult.Saved -> "$saved，并已保存到云端"
            })
        } catch(error: CancellationException) { throw error }
        catch(error: Exception) { message("$saved；云端未保存：${error.friendlyMessage()}") }
    }
}

private fun AppController.requireReadyForFavoriteSync(binding: SessionBinding) {
    session.ensureCurrent(binding)
    require(binding.account != null) { "请先登录原站账号。" }
    require(store.recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复。" }
    require(store.state.value.pending.none { it.account == binding.account &&
        (it.path.startsWith("user/favored-web/") || it.path.startsWith("user/favored-wenku/")) }) {
        "当前账号有待处理的云端收藏，请先到「网络与同步」完成同步，再重新检查。"
    }
}

private suspend fun AppController.readFavoriteShelf(binding: SessionBinding, onStatus: (FavoriteSyncProgress) -> Unit): CloudFavoriteFolderImport {
    requireReadyForFavoriteSync(binding)
    val validate = { session.ensureCurrent(binding) }
    val books = listOf(false, true).flatMap { wenku ->
        val phase = if(wenku) FavoriteSyncPhase.ReadWenku else FavoriteSyncPhase.ReadWeb
        onStatus(FavoriteSyncProgress(phase))
        api.allCloudFavorites(wenku, validate) { onStatus(FavoriteSyncProgress(phase, it.pagesRead, it.pageCount, it.booksRead)) }
    }
    onStatus(FavoriteSyncProgress(FavoriteSyncPhase.ResolveFolders))
    val cloud = api.resolveCloudFavoriteFolders(books, validate) {
        onStatus(FavoriteSyncProgress(FavoriteSyncPhase.ResolveFolders, it, books.size))
    }
    requireReadyForFavoriteSync(binding)
    onStatus(FavoriteSyncProgress(FavoriteSyncPhase.Compare))
    return cloud
}

internal suspend fun AppController.previewFavoriteSync(binding: SessionBinding, priority: FavoriteSyncPriority,
    onStatus: (FavoriteSyncProgress) -> Unit): FavoriteSyncPlan {
    val cloud = readFavoriteShelf(binding, onStatus)
    return withContext(Dispatchers.Default) { store.state.value.planFavoriteSync(priority, cloud) }
}

internal suspend fun AppController.applyFavoriteSync(binding: SessionBinding, approved: FavoriteSyncPlan,
    onStatus: (FavoriteSyncProgress) -> Unit, onProgress: (Int, Int) -> Unit) {
    val cloud = readFavoriteShelf(binding, onStatus)
    if(approved.priority == FavoriteSyncPriority.Cloud) {
        onStatus(FavoriteSyncProgress(FavoriteSyncPhase.Write, total = approved.changes.size))
        withContext(Dispatchers.Default) {
            store.update { state ->
                requireReadyForFavoriteSync(binding)
                val current = state.planFavoriteSync(approved.priority, cloud)
                approved.requireSameChanges(current)
                state.applyCloudPriority(current)
            }
        }
        onProgress(approved.changes.size, 0)
    } else {
        withContext(Dispatchers.Default) { approved.requireSameChanges(store.state.value.planFavoriteSync(approved.priority, cloud)) }
        requireReadyForFavoriteSync(binding)
        onStatus(FavoriteSyncProgress(FavoriteSyncPhase.Write, total = approved.changes.size))
        approved.applyLocalPriority({ session.ensureCurrent(binding) }, createFolder = { wenku, title ->
            api.request("POST", cloudFavoriteCollection(wenku), appJson.encodeToString(mapOf("title" to title)), binding = binding)
        }, put = { ref, folder -> cloudMutation("PUT", ref.cloudFavoritePath(folder), notifyQueued = false, binding = binding) },
            onProgress = onProgress)
    }
    session.ensureCurrent(binding)
}
