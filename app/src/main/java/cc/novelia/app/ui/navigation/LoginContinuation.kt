package cc.novelia.app.ui.navigation

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val FAVORITE_AFTER_LOGIN = "novelia.favoriteAfterLogin"
private const val FORUM_FAVORITE_AFTER_LOGIN = "novelia.forumFavoriteAfterLogin"
internal const val FORUM_FAVORITE_REQUEST = "novelia.forumFavoriteRequest"

@Serializable internal data class ForumFavoriteRequest(val postId: Long, val account: String?, val generation: Long,
    val source: String = "forum", val sourceRevision: Long = 0,
) {
    fun matches(postId: Long?, binding: SessionBinding) =
        account != null && this.postId == postId && account == binding.account && generation == binding.generation &&
            source == binding.source && sourceRevision == binding.sourceRevision
}

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

/** 论坛收藏续接绑定此次登录项，取消登录不会留下待执行的收藏。 */
internal fun loginForForumFavorite(c: AppController, postId: Long) {
    require(postId > 0)
    c.afterLogin = null
    c.go("forum-login")
    c.nav.currentBackStackEntry?.savedStateHandle?.set(FORUM_FAVORITE_AFTER_LOGIN, postId)
}

/** 仅认证成功后调用；取消登录会丢弃整个登录返回栈项。 */
internal fun finishLoginNavigation(c: AppController, forum: Boolean = false) {
    val entry = c.nav.currentBackStackEntry?.takeIf { it.destination.route == if(forum) "forum-login" else "login" } ?: return
    val favorite = if(forum) null else entry.savedStateHandle.remove<String>(FAVORITE_AFTER_LOGIN)
        ?.let { runCatching { appJson.decodeFromString<BookCard>(it) }.getOrNull() }
    val forumFavorite = if(forum) entry.savedStateHandle.remove<Long>(FORUM_FAVORITE_AFTER_LOGIN) else null
    val forumRequest = forumFavorite?.let {
        val binding = c.forumSession.capture()
        appJson.encodeToString(ForumFavoriteRequest(it, binding.account, binding.generation, binding.source, binding.sourceRevision))
    }
    val returnEntry = c.nav.previousBackStackEntry
    val continuation = c.afterLogin
    c.afterLogin = null
    c.back()
    if(forumRequest != null) {
        returnEntry?.savedStateHandle?.set(FORUM_FAVORITE_REQUEST, forumRequest)
    } else if(favorite != null) {
        c.pendingFavoriteCloud = true
        c.pendingFavorite = favorite
    } else continuation?.invoke()
}
