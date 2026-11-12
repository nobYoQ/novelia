package cc.novelia.app.ui.shelf

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.sync.pendingBookKey

internal fun pendingFavoriteAction(pending: List<PendingAction>, account: String, ref: BookRef): PendingAction? {
    val prefix = if(ref.isWenku) "user/favored-wenku/" else "user/favored-web/"
    return pending.lastOrNull { it.account == account && it.method in listOf("PUT", "DELETE") && it.path.startsWith(prefix) && pendingBookKey(it) == ref.key }
}

internal data class BookFavoriteState(val local: Boolean, val cloudFolder: String?, val pendingMethod: String? = null,
    val localFolderName: String? = null, val cloudFolderName: String? = null) {
    val isSaved get() = local || cloudFolder != null
    val localIndicatorLabel get() = localFolderName ?: "本地收藏"
    val cloudIndicatorLabel get() = when {
        cloudFolderName != null && pendingMethod == "PUT" -> "$cloudFolderName · 待同步"
        cloudFolderName != null -> cloudFolderName
        pendingMethod == "PUT" -> "云端收藏待同步"
        else -> "云端收藏"
    }
    val localLabel get() = if(local) "已本地收藏" else "收藏到本地"
    val cloudLabel get() = when {
        pendingMethod == "PUT" -> "云端收藏待同步"
        pendingMethod == "DELETE" -> "取消云端收藏待同步"
        cloudFolder != null -> "已云端收藏"
        else -> "收藏到云端"
    }
}

/** 云端收藏归属按账号区分，本地副本不能掩盖当前账号的收藏状态。 */
internal fun bookFavoriteState(
    ref: BookRef,
    local: Boolean,
    cloudFolder: String?,
    account: String?,
    pending: List<PendingAction>,
    localFolderName: String? = null,
    cloudFolders: CloudFolders? = null,
): BookFavoriteState {
    val localName = localFolderName?.takeIf { local && it.isNotBlank() }
    if(account == null || ref.isLocal) return BookFavoriteState(local, null, localFolderName = localName)
    val action = pendingFavoriteAction(pending, account, ref)
    val folder = when(action?.method) {
        "PUT" -> action.path.split('/').getOrNull(2)?.takeIf(String::isNotBlank)
        "DELETE" -> null
        else -> cloudFolder?.takeIf(String::isNotBlank)
    }
    val folders = if(ref.isWenku) cloudFolders?.favoredWenku else cloudFolders?.favoredWeb
    val cloudName = folders?.firstOrNull { it.id == folder }?.title?.takeIf(String::isNotBlank)
    return BookFavoriteState(local, folder, action?.method, localName, cloudName)
}
