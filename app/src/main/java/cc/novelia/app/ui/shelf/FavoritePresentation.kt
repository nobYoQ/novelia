package cc.novelia.app.ui.shelf

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.PendingAction

internal fun pendingFavoriteAction(pending: List<PendingAction>, account: String, ref: BookRef): PendingAction? {
    val prefix = if(ref.isWenku) "user/favored-wenku/" else "user/favored-web/"
    val suffix = "/${if(ref.isWenku) ref.id else ref.key}"
    return pending.lastOrNull { it.account == account && it.path.startsWith(prefix) && it.path.substringAfter(prefix).substringAfter('/') == suffix.removePrefix("/") }
}
