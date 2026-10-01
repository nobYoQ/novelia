package cc.novelia.app.ui.shelf

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.sync.pendingBookKey

internal fun pendingFavoriteAction(pending: List<PendingAction>, account: String, ref: BookRef): PendingAction? {
    val prefix = if(ref.isWenku) "user/favored-wenku/" else "user/favored-web/"
    return pending.lastOrNull { it.account == account && it.method in listOf("PUT", "DELETE") && it.path.startsWith(prefix) && pendingBookKey(it) == ref.key }
}

internal data class BookFavoriteState(val local: Boolean, val cloudFolder: String?, val pendingMethod: String? = null) {
    val isSaved get() = local || cloudFolder != null
    val label get() = when {
        pendingMethod == "PUT" -> "云端收藏待同步 · 管理收藏"
        pendingMethod == "DELETE" -> "取消云端收藏待同步 · 管理收藏"
        cloudFolder != null -> "已云端收藏 · 管理收藏"
        local -> "已本地收藏 · 管理收藏"
        else -> "收藏到书架"
    }
}

/** 云端收藏归属按账号区分，本地副本不能掩盖当前账号的收藏状态。 */
internal fun bookFavoriteState(
    ref: BookRef,
    local: Boolean,
    cloudFolder: String?,
    account: String?,
    pending: List<PendingAction>,
): BookFavoriteState {
    if(account == null || ref.isLocal) return BookFavoriteState(local, null)
    val action = pendingFavoriteAction(pending, account, ref)
    val folder = when(action?.method) {
        "PUT" -> action.path.split('/').getOrNull(2)?.takeIf(String::isNotBlank)
        "DELETE" -> null
        else -> cloudFolder?.takeIf(String::isNotBlank)
    }
    return BookFavoriteState(local, folder, action?.method)
}
