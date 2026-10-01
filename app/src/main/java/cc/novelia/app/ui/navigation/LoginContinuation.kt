package cc.novelia.app.ui.navigation

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString

private const val FAVORITE_AFTER_LOGIN = "novelia.favoriteAfterLogin"

/**
 * 将待收藏书目序列化到此次登录导航项，旋转或进程重建后仍可续接。
 * 登录前清除旧 afterLogin 回调，取消本次登录时意图随导航项一起丢弃，避免串入后续登录。
 */
internal fun loginForFavorite(c: AppController, book: BookCard) {
    if(c.session.profile.value != null) {
        c.pendingFavoriteCloud = true
        c.pendingFavorite = book
        return
    }
    c.afterLogin = null
    c.go("login")
    c.nav.currentBackStackEntry?.savedStateHandle?.set(FAVORITE_AFTER_LOGIN, appJson.encodeToString(book))
}

/** 仅认证成功后调用；取消登录会丢弃整个登录返回栈项。 */
internal fun finishLoginNavigation(c: AppController) {
    val entry = c.nav.currentBackStackEntry?.takeIf { it.destination.route == "login" } ?: return
    val favorite = entry.savedStateHandle.remove<String>(FAVORITE_AFTER_LOGIN)
        ?.let { runCatching { appJson.decodeFromString<BookCard>(it) }.getOrNull() }
    val continuation = c.afterLogin
    c.afterLogin = null
    c.back()
    if(favorite != null) {
        c.pendingFavoriteCloud = true
        c.pendingFavorite = favorite
    } else continuation?.invoke()
}
