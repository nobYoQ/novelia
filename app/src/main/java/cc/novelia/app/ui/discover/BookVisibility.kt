package cc.novelia.app.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.ui.shelf.bookFavoriteState
import cc.novelia.app.ui.components.base.EmptyState

@Composable internal fun HiddenDiscoverPage(count: Int, hasNext: Boolean, onNext: () -> Unit, onSettings: () -> Unit) {
    EmptyState("本页 ${count} 本均被当前规则隐藏",
        if(hasNext) "可以保留当前条件，继续看下一页。" else "已到最后一页，可检查屏蔽和收藏隐藏设置。",
        Icons.Outlined.VisibilityOff, if(hasNext) "看下一页" else "检查屏蔽条件",
        if(hasNext) onNext else onSettings)
}

/** 仅过滤发现与榜单，不改变收藏关系；云端采用当前账号列表响应及其待同步操作。 */
internal data class BookVisibility(
    val blockedBooks: Set<String>, val blockedTags: Set<String>, val blockedAuthors: Set<String>,
    val hiddenLocalFavorites: Set<String>, val hideCloudFavorites: Boolean,
    val account: String?, val pending: List<PendingAction>,
) {
    fun visible(book: BookCard): Boolean = book.ref.key !in blockedBooks &&
        book.tags.none { it in blockedTags } &&
        book.authors.none { author -> blockedAuthors.any { it.equals(author.trim(), ignoreCase = true) } } &&
        book.ref.key !in hiddenLocalFavorites &&
        (!hideCloudFavorites || bookFavoriteState(book.ref, false, book.favored, account, pending).cloudFolder == null)
}

private fun bookVisibility(state: LibraryState, account: String?) = BookVisibility(
    state.blockedBooks, state.blockedTags, state.blockedAuthors,
    if(state.hideLocalFavorites) state.books.mapTo(mutableSetOf()) { it.book.ref.key } else emptySet(),
    state.hideCloudFavorites, account,
    if(state.hideCloudFavorites && account != null) state.pending.filter {
        it.account == account && it.method in listOf("PUT", "DELETE") &&
            (it.path.startsWith("user/favored-web/") || it.path.startsWith("user/favored-wenku/"))
    } else emptyList(),
)

fun visibleBook(book: BookCard, state: LibraryState, account: String? = null): Boolean = bookVisibility(state, account).visible(book)

@Composable internal fun rememberBookVisibility(state: LibraryState, account: String?): BookVisibility =
    remember(state.blockedBooks, state.blockedTags, state.blockedAuthors, state.books,
        state.hideLocalFavorites, state.hideCloudFavorites, state.pending, account) { bookVisibility(state, account) }
