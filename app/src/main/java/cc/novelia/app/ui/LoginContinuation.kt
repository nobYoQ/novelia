package cc.novelia.app.ui

import cc.novelia.app.data.BookCard
import cc.novelia.app.data.appJson
import kotlinx.serialization.encodeToString

private const val FAVORITE_AFTER_LOGIN = "novelia.favoriteAfterLogin"

/** Store the concrete intent in the login entry so NavHost restores it after recreation. */
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

/** Call only after authentication succeeds. A cancelled login discards its whole back-stack entry. */
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
